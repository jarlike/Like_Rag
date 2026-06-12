package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.SearchHit;
import cn.like.rag.sentinel.SentinelGuard;
import cn.like.rag.sentinel.SentinelResources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义重排序（Reranker）：在 Hybrid(RRF+MMR) 之后、ContextCompressor 之前，用 LLM 对候选片段做
 * listwise 相关性打分（0~3）并重排，提升 nDCG/MRR。对应《重排序与多轮对话项目书》第三章。
 *
 * <p>设计原则：<b>best-effort + 优雅降级</b>。任何一种"用不了"的情况——开关关闭、未配置 Key、
 * 候选过少、被 Sentinel {@code service.rerank} 并发隔离拦截、LLM 调用/解析失败——都<b>原样退回输入排序</b>
 * （裁剪到 topK），绝不让重排序故障影响问答主链路。这与本项目"始终可运行"的设计一致。
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private static final String SYSTEM_PROMPT =
            "你是检索结果重排序器。给定一个问题和若干候选片段，判定每个候选与问题的相关性等级：\n"
                    + "0=无关，1=弱相关（仅背景信息），2=相关（能支撑部分回答），3=强相关（能直接支撑关键结论）。\n"
                    + "只输出一个 JSON 数组，每个元素形如 {\"id\":候选编号,\"score\":相关性等级}，"
                    + "覆盖所有候选编号，按相关性从高到低排序。不要输出 JSON 以外的任何文字。";

    private final OpenAiClientService openAiClientService;
    private final ObjectMapper objectMapper;
    private final RagProperties properties;
    private final SentinelGuard sentinelGuard;
    private final OperationLogService operationLogService;

    public RerankService(OpenAiClientService openAiClientService,
                         ObjectMapper objectMapper,
                         RagProperties properties,
                         SentinelGuard sentinelGuard,
                         OperationLogService operationLogService) {
        this.openAiClientService = openAiClientService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.sentinelGuard = sentinelGuard;
        this.operationLogService = operationLogService;
    }

    /**
     * 对候选片段重排序并裁剪到 topK。失败/降级时返回输入顺序裁剪到 topK 的结果。
     */
    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK) {
        int limit = Math.max(1, topK);
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        List<SearchHit> trimmed = trim(candidates, limit);
        RagProperties.Rerank cfg = properties.getRerank();
        if (!cfg.isEnabled()
                || candidates.size() <= 1
                || query == null || query.isBlank()
                || !openAiClientService.isConfigured()) {
            return trimmed;
        }

        try {
            // 被 service.rerank 并发隔离拦截时，callOrElse 直接返回原排序（trimmed）。
            return sentinelGuard.callOrElse(
                    SentinelResources.SERVICE_RERANK,
                    () -> doRerank(query, candidates, limit, cfg),
                    () -> trimmed);
        } catch (RuntimeException e) {
            // LLM 调用/解析等业务异常：降级回原排序，不影响问答主链路。
            operationLogService.error("RERANK_DEGRADED", "重排序失败，退回原排序", null,
                    Map.of("error", String.valueOf(e.getMessage()), "candidates", candidates.size()));
            return trimmed;
        }
    }

    private List<SearchHit> doRerank(String query, List<SearchHit> candidates, int topK, RagProperties.Rerank cfg) {
        String user = buildPrompt(query, candidates, cfg.getMaxCandidateChars());
        String output = openAiClientService.complete(SYSTEM_PROMPT, user, cfg.getMaxOutputTokens());
        Map<Integer, Double> idToScore = parseScores(output, candidates.size());
        List<SearchHit> ranked = applyRanking(candidates, idToScore, topK);
        operationLogService.info("RERANK", "重排序完成", null,
                Map.of("candidates", candidates.size(), "scored", idToScore.size(), "topK", topK));
        return ranked;
    }

    private String buildPrompt(String query, List<SearchHit> candidates, int maxCandidateChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(query.trim()).append("\n\n候选片段：\n");
        for (int i = 0; i < candidates.size(); i++) {
            SearchHit hit = candidates.get(i);
            String doc = hit.getChunk() == null ? "" : hit.getChunk().getDocumentName();
            String section = hit.getChunk() == null ? null : hit.getChunk().getSectionPath();
            sb.append("[").append(i + 1).append("] document=").append(doc);
            if (section != null && !section.isBlank()) {
                sb.append(" | section=").append(section);
            }
            sb.append("\n").append(snippet(text(hit), maxCandidateChars)).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 从模型输出里解析出 id→相关性等级。容忍 ```json 围栏与多余文字：只取第一个 [ 到最后一个 ] 之间的数组。
     * 返回 1-based 候选编号到 [0,3] 等级的映射；解析不出则返回空表（上层据此退回原排序）。
     */
    Map<Integer, Double> parseScores(String output, int candidateCount) {
        Map<Integer, Double> result = new LinkedHashMap<>();
        String json = extractJsonArray(output);
        if (json == null) {
            return result;
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                return result;
            }
            for (JsonNode item : array) {
                if (!item.has("id")) {
                    continue;
                }
                int id = item.path("id").asInt(-1);
                if (id < 1 || id > candidateCount) {
                    continue;
                }
                double score = item.path("score").asDouble(0.0);
                score = Math.max(0.0, Math.min(3.0, score));
                result.putIfAbsent(id, score);
            }
        } catch (Exception e) {
            log.warn("Rerank output parse failed: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
        return result;
    }

    /**
     * 按等级降序稳定重排（等级相同/缺失保持原相对顺序），把命中的相关性等级归一化到 [0,1] 写入 score，
     * 缺失候选记 0 分排到最后，最后裁剪到 topK。idToScore 为空时直接返回原排序裁剪结果。
     */
    List<SearchHit> applyRanking(List<SearchHit> candidates, Map<Integer, Double> idToScore, int topK) {
        if (idToScore.isEmpty()) {
            return trim(candidates, topK);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        // List.sort 是稳定排序：分数相等时保留原有相对顺序。
        order.sort((a, b) -> Double.compare(
                idToScore.getOrDefault(b + 1, -1.0),
                idToScore.getOrDefault(a + 1, -1.0)));

        List<SearchHit> ranked = new ArrayList<>();
        for (int idx : order) {
            SearchHit hit = candidates.get(idx);
            double level = idToScore.getOrDefault(idx + 1, 0.0);
            hit.setScore(level / 3.0);
            ranked.add(hit);
        }
        return trim(ranked, topK);
    }

    private static String extractJsonArray(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static List<SearchHit> trim(List<SearchHit> hits, int topK) {
        if (hits.size() <= topK) {
            return hits;
        }
        return new ArrayList<>(hits.subList(0, topK));
    }

    private static String text(SearchHit hit) {
        return hit.getChunk() == null ? "" : hit.getChunk().getText();
    }

    private static String snippet(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        int max = Math.max(1, maxChars);
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "…";
    }
}

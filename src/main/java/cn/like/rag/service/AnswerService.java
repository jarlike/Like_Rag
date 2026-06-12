package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.ChatResponse;
import cn.like.rag.model.SearchHit;
import cn.like.rag.sentinel.SentinelGuard;
import cn.like.rag.sentinel.SentinelResources;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnswerService {

    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final ContextCompressor contextCompressor;
    private final OpenAiClientService openAiClientService;
    private final ConversationMemoryService conversationMemory;
    private final QueryRewriteService queryRewriteService;
    private final OperationLogService operationLogService;
    private final SentinelGuard sentinelGuard;
    private final RagProperties properties;

    public AnswerService(HybridSearchService hybridSearchService,
                         RerankService rerankService,
                         ContextCompressor contextCompressor,
                         OpenAiClientService openAiClientService,
                         ConversationMemoryService conversationMemory,
                         QueryRewriteService queryRewriteService,
                         OperationLogService operationLogService,
                         SentinelGuard sentinelGuard,
                         RagProperties properties) {
        this.hybridSearchService = hybridSearchService;
        this.rerankService = rerankService;
        this.contextCompressor = contextCompressor;
        this.openAiClientService = openAiClientService;
        this.conversationMemory = conversationMemory;
        this.queryRewriteService = queryRewriteService;
        this.operationLogService = operationLogService;
        this.sentinelGuard = sentinelGuard;
        this.properties = properties;
    }

    public ChatResponse answer(String question, Integer topK) {
        return answer(question, topK, null);
    }

    /**
     * 多轮问答：sessionId 非空且会话启用时，先用历史对追问做 query 改写（指代消解），
     * 再走 检索 → 重排序 → 上下文压缩 → GPT 生成，最后把本轮问答写入会话记忆。
     * sessionId 为空时退化为独立单轮问答（与旧行为一致）。
     */
    public ChatResponse answer(String question, Integer topK, String sessionId) {
        if (!openAiClientService.isConfigured()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured, cannot call GPT-5.5");
        }

        int finalK = topK == null ? properties.getFinalTopK() : Math.max(1, topK);

        // 多轮：基于历史改写追问；无历史或改写关闭/失败时用原问题检索。
        String historyText = conversationMemory.historyText(sessionId);
        String searchQuery = historyText.isBlank()
                ? question
                : queryRewriteService.rewrite(question, historyText);

        // 重排序开启时，从 Hybrid 多取候选池（candidateK），重排后裁剪到 finalK；关闭时直接取 finalK。
        int candidateK = properties.getRerank().isEnabled()
                ? Math.max(finalK, properties.getRerank().getCandidateK())
                : finalK;
        List<SearchHit> candidates = hybridSearchService.search(searchQuery, candidateK);
        List<SearchHit> hits = rerankService.rerank(searchQuery, candidates, finalK);
        hits = contextCompressor.compress(searchQuery, hits);

        ChatResponse response = new ChatResponse();
        response.setQuestion(question);
        response.setSessionId(sessionId);
        if (!searchQuery.equals(question)) {
            response.setRewrittenQuestion(searchQuery);
        }
        response.setHits(hits);
        response.setCitations(toCitations(hits));

        final List<SearchHit> finalHits = hits;
        final String conversationContext = historyText;
        // LLM 生成受 Sentinel service.llmGenerate 保护（并发隔离 + 慢调用/异常比例熔断）。
        // 命中保护时返回"明确的限流/熔断降级"回答（附 TopK 证据），而非静默把模型降级为兜底——
        // 与 force-GPT 策略一致：单次失败仍向上抛错由熔断统计，只有真正限流/熔断才给降级回答。
        sentinelGuard.call(SentinelResources.SERVICE_LLM_GENERATE,
                () -> {
                    response.setProvider("openai");
                    response.setModel(openAiClientService.chatModel());
                    response.setAnswer(generateGptAnswer(question, finalHits, conversationContext));
                    return null;
                },
                (resource, blockEx) -> {
                    response.setProvider("degraded");
                    response.setModel(openAiClientService.chatModel());
                    response.setAnswer(degradedAnswer(finalHits));
                    operationLogService.error("LLM_DEGRADED",
                            "LLM 生成被 Sentinel 限流/熔断，返回证据降级回答", null,
                            Map.of("resource", resource,
                                    "blockType", blockEx.getClass().getSimpleName(),
                                    "hitCount", finalHits.size()));
                    return null;
                });

        // 仅在拿到真实 GPT 回答时写入会话记忆，避免把"服务繁忙"降级文案污染后续追问改写。
        if ("openai".equals(response.getProvider()) && conversationMemory.isActive(sessionId)) {
            conversationMemory.record(sessionId, question, response.getAnswer());
        }

        Map<String, Object> logDetail = new LinkedHashMap<>();
        logDetail.put("question", question);
        if (response.getRewrittenQuestion() != null) {
            logDetail.put("rewrittenQuestion", response.getRewrittenQuestion());
        }
        logDetail.put("sessionId", sessionId == null ? "-" : sessionId);
        logDetail.put("hitCount", hits.size());
        logDetail.put("provider", response.getProvider());
        logDetail.put("model", openAiClientService.chatModel());
        operationLogService.info("CHAT", "Question answered", null, logDetail);
        return response;
    }

    private String generateGptAnswer(String question, List<SearchHit> hits, String conversationContext) {
        if (hits.isEmpty()) {
            return "当前知识库没有检索到足够相关的内容，建议先上传相关文档或换一种问法。";
        }
        try {
            return openAiClientService.generateAnswer(question, hits, conversationContext);
        } catch (Exception e) {
            operationLogService.error("OPENAI_ANSWER_FAILED", "OpenAI answer generation failed", null,
                    Map.of("model", openAiClientService.chatModel(), "error", String.valueOf(e.getMessage())));
            throw new IllegalStateException("GPT-5.5 answer generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 限流/熔断降级回答：明确告知生成服务被保护性限流/熔断（非"知识库无答案"），并附最相关证据片段。
     */
    private String degradedAnswer(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "⚠️ 生成服务当前繁忙或被熔断（Sentinel 保护），且未检索到相关证据，请稍后重试。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 生成服务当前繁忙或被熔断（Sentinel 保护），暂时无法调用 GPT-5.5 生成完整回答。")
                .append("以下是检索到的最相关证据片段，请稍后重试：\n");
        int limit = Math.min(hits.size(), 3);
        for (int i = 0; i < limit; i++) {
            SearchHit hit = hits.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(hit.getChunk().getDocumentName())
                    .append("：")
                    .append(snippet(hit.getChunk().getText(), 180))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private List<ChatResponse.Citation> toCitations(List<SearchHit> hits) {
        return hits.stream()
                .map(hit -> {
                    ChatResponse.Citation citation = new ChatResponse.Citation();
                    citation.setDocumentId(hit.getChunk().getDocumentId());
                    citation.setDocumentName(hit.getChunk().getDocumentName());
                    citation.setChunkIndex(hit.getChunk().getChunkIndex());
                    citation.setSectionPath(hit.getChunk().getSectionPath());
                    citation.setScore(hit.getScore());
                    citation.setSnippet(snippet(hit.getChunk().getText(), 180));
                    return citation;
                })
                .toList();
    }

    private String snippet(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}

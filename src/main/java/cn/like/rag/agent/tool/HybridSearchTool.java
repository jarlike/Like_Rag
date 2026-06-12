package cn.like.rag.agent.tool;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.SearchHit;
import cn.like.rag.service.HybridSearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ReAct 工具：执行混合检索（dense + sparse → RRF → MMR）。证据不足或需要二次检索时由 Agent 调用。
 */
@Component
public class HybridSearchTool implements AgentTool {

    private final HybridSearchService hybridSearchService;
    private final RagProperties properties;

    public HybridSearchTool(HybridSearchService hybridSearchService, RagProperties properties) {
        this.hybridSearchService = hybridSearchService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "hybrid_search";
    }

    @Override
    public String description() {
        return "混合检索知识库（dense+sparse 融合去重）。入参 {\"query\": \"检索词\", \"topK\": 5}。"
                + "返回带编号的证据片段，编号可用于答案引用 [n]。证据不足/换问法时调用。";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String query = ToolArgs.asString(args, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.text("ERROR: hybrid_search 需要非空的 query 参数");
        }
        int topK = ToolArgs.asInt(args, "topK", properties.getAgent().getToolSearchTopK());
        topK = Math.max(1, Math.min(topK, 20));
        List<SearchHit> hits = hybridSearchService.search(query, topK);
        if (hits.isEmpty()) {
            return ToolResult.withHits("未检索到相关片段（query=\"" + query + "\"）。建议改写 query 或换关键词。", hits);
        }
        StringBuilder sb = new StringBuilder("检索到 " + hits.size() + " 条证据（query=\"" + query + "\"）：\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append("score=").append(String.format(Locale.ROOT, "%.4f", hit.getScore()))
                    .append(" | doc=").append(hit.getChunk().getDocumentName())
                    .append(" | chunk=").append(hit.getChunk().getChunkIndex())
                    .append("\n")
                    .append(snippet(hit.getChunk().getText(), 160))
                    .append("\n");
        }
        return ToolResult.withHits(sb.toString().trim(), hits);
    }

    private String snippet(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }
}

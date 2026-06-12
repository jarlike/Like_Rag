package cn.like.rag.sentinel;

/**
 * Sentinel 资源名常量，对应项目书第五章第 2 节《资源定义》。
 * 统一在一处声明，避免散落的魔法字符串导致规则与埋点对不上。
 */
public final class SentinelResources {

    /** 文档上传入口：POST /api/documents。保护上传 QPS。 */
    public static final String API_UPLOAD = "api.document.upload";

    /** 重建索引入口：POST /api/documents/{id}/reindex。保护重建并发数。 */
    public static final String API_REINDEX = "api.document.reindex";

    /** 问答入口：POST /api/chat。保护问答 QPS。 */
    public static final String API_CHAT = "api.chat";

    /** Agent 入口：POST /api/agent/chat。复用问答 QPS 思路并叠加工具调用预算。 */
    public static final String API_AGENT = "api.agent";

    /** 混合检索服务：HybridSearchService.search。保护检索并发。 */
    public static final String SERVICE_HYBRID_SEARCH = "service.hybridSearch";

    /** Embedding 调用：批量索引时的向量化。保护 embedding 并发与异常比例。 */
    public static final String SERVICE_EMBEDDING = "service.embedding";

    /** LLM 生成：GPT-5.5 问答生成。保护慢调用、异常比例、并发，触发熔断。 */
    public static final String SERVICE_LLM_GENERATE = "service.llmGenerate";

    /** 语义重排序：RerankService 的 LLM listwise 重排。保护并发，超限时降级回原排序。 */
    public static final String SERVICE_RERANK = "service.rerank";

    /** Agent 工具调用循环：约束单轮最大调用次数与总耗时。 */
    public static final String AGENT_TOOL_LOOP = "agent.toolLoop";

    private SentinelResources() {
    }
}

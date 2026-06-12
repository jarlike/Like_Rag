package cn.like.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private String storageRoot = "data/rag";
    private int chunkMaxTokens = 420;
    private int chunkOverlapTokens = 60;
    private int topK = 5;
    private int sparseTopK = 50;
    private int denseTopK = 50;
    private int finalTopK = 10;
    private int rrfK = 60;
    private double mmrLambda = 0.7;
    private double duplicateThreshold = 0.92;
    private int contextTokenBudget = 6000;
    private int reservedPromptTokens = 800;
    private int maxChunkContextTokens = 800;
    private int embeddingDimension = 384;
    private boolean asyncIndexFallback = true;
    private     OpenAi openai = new OpenAi();
    private Mq mq = new Mq();
    private Sentinel sentinel = new Sentinel();
    private Agent agent = new Agent();
    private Eval eval = new Eval();
    private Rerank rerank = new Rerank();
    private Conversation conversation = new Conversation();

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public int getChunkMaxTokens() {
        return chunkMaxTokens;
    }

    public void setChunkMaxTokens(int chunkMaxTokens) {
        this.chunkMaxTokens = chunkMaxTokens;
    }

    public int getChunkOverlapTokens() {
        return chunkOverlapTokens;
    }

    public void setChunkOverlapTokens(int chunkOverlapTokens) {
        this.chunkOverlapTokens = chunkOverlapTokens;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getSparseTopK() {
        return sparseTopK;
    }

    public void setSparseTopK(int sparseTopK) {
        this.sparseTopK = sparseTopK;
    }

    public int getDenseTopK() {
        return denseTopK;
    }

    public void setDenseTopK(int denseTopK) {
        this.denseTopK = denseTopK;
    }

    public int getFinalTopK() {
        return finalTopK;
    }

    public void setFinalTopK(int finalTopK) {
        this.finalTopK = finalTopK;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public double getMmrLambda() {
        return mmrLambda;
    }

    public void setMmrLambda(double mmrLambda) {
        this.mmrLambda = mmrLambda;
    }

    public double getDuplicateThreshold() {
        return duplicateThreshold;
    }

    public void setDuplicateThreshold(double duplicateThreshold) {
        this.duplicateThreshold = duplicateThreshold;
    }

    public int getContextTokenBudget() {
        return contextTokenBudget;
    }

    public void setContextTokenBudget(int contextTokenBudget) {
        this.contextTokenBudget = contextTokenBudget;
    }

    public int getReservedPromptTokens() {
        return reservedPromptTokens;
    }

    public void setReservedPromptTokens(int reservedPromptTokens) {
        this.reservedPromptTokens = reservedPromptTokens;
    }

    public int getMaxChunkContextTokens() {
        return maxChunkContextTokens;
    }

    public void setMaxChunkContextTokens(int maxChunkContextTokens) {
        this.maxChunkContextTokens = maxChunkContextTokens;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public boolean isAsyncIndexFallback() {
        return asyncIndexFallback;
    }

    public void setAsyncIndexFallback(boolean asyncIndexFallback) {
        this.asyncIndexFallback = asyncIndexFallback;
    }

    public OpenAi getOpenai() {
        return openai;
    }

    public void setOpenai(OpenAi openai) {
        this.openai = openai;
    }

    public Mq getMq() {
        return mq;
    }

    public void setMq(Mq mq) {
        this.mq = mq;
    }

    public Sentinel getSentinel() {
        return sentinel;
    }

    public void setSentinel(Sentinel sentinel) {
        this.sentinel = sentinel;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public Eval getEval() {
        return eval;
    }

    public void setEval(Eval eval) {
        this.eval = eval;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public void setRerank(Rerank rerank) {
        this.rerank = rerank;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public static class OpenAi {
        private String apiKey = "";
        private String baseUrl = "http://localhost:8080/v1";
        private String chatModel = "gpt-5.5";
        private String chatEndpoint = "chat-completions";
        private String embeddingModel = "text-embedding-3-small";
        private int embeddingDimensions = 384;
        private boolean embeddingEnabled = false;
        private int timeoutSeconds = 60;
        private int maxRetries = 2;
        private long retryBackoffMillis = 500;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getChatEndpoint() {
            return chatEndpoint;
        }

        public void setChatEndpoint(String chatEndpoint) {
            this.chatEndpoint = chatEndpoint;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingDimensions() {
            return embeddingDimensions;
        }

        public void setEmbeddingDimensions(int embeddingDimensions) {
            this.embeddingDimensions = embeddingDimensions;
        }

        public boolean isEmbeddingEnabled() {
            return embeddingEnabled;
        }

        public void setEmbeddingEnabled(boolean embeddingEnabled) {
            this.embeddingEnabled = embeddingEnabled;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getRetryBackoffMillis() {
            return retryBackoffMillis;
        }

        public void setRetryBackoffMillis(long retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
        }
    }

    public static class Mq {
        private boolean enabled = true;
        private String topic = "like-rag-index-topic";
        private String consumerGroup = "like-rag-index-consumer";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }
    }

    /**
     * Sentinel 限流 / 熔断开关与阈值。对应项目书第五章资源定义与规则设计。
     * 规则在 {@code SentinelConfig} 中以编程方式加载，无需 Sentinel 控制台。
     */
    public static class Sentinel {
        private boolean enabled = true;
        private int uploadQps = 1;
        private int chatQps = 5;
        private int reindexConcurrency = 1;
        private int embeddingMaxConcurrency = 4;
        private int hybridSearchMaxConcurrency = 8;
        private int llmMaxConcurrency = 3;
        private int rerankMaxConcurrency = 3;
        private int llmSlowCallRtMillis = 5000;
        private double exceptionRatio = 0.3;
        private int circuitBreakWindowSeconds = 30;
        private int minRequestAmount = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getUploadQps() {
            return uploadQps;
        }

        public void setUploadQps(int uploadQps) {
            this.uploadQps = uploadQps;
        }

        public int getChatQps() {
            return chatQps;
        }

        public void setChatQps(int chatQps) {
            this.chatQps = chatQps;
        }

        public int getReindexConcurrency() {
            return reindexConcurrency;
        }

        public void setReindexConcurrency(int reindexConcurrency) {
            this.reindexConcurrency = reindexConcurrency;
        }

        public int getEmbeddingMaxConcurrency() {
            return embeddingMaxConcurrency;
        }

        public void setEmbeddingMaxConcurrency(int embeddingMaxConcurrency) {
            this.embeddingMaxConcurrency = embeddingMaxConcurrency;
        }

        public int getHybridSearchMaxConcurrency() {
            return hybridSearchMaxConcurrency;
        }

        public void setHybridSearchMaxConcurrency(int hybridSearchMaxConcurrency) {
            this.hybridSearchMaxConcurrency = hybridSearchMaxConcurrency;
        }

        public int getLlmMaxConcurrency() {
            return llmMaxConcurrency;
        }

        public void setLlmMaxConcurrency(int llmMaxConcurrency) {
            this.llmMaxConcurrency = llmMaxConcurrency;
        }

        public int getRerankMaxConcurrency() {
            return rerankMaxConcurrency;
        }

        public void setRerankMaxConcurrency(int rerankMaxConcurrency) {
            this.rerankMaxConcurrency = rerankMaxConcurrency;
        }

        public int getLlmSlowCallRtMillis() {
            return llmSlowCallRtMillis;
        }

        public void setLlmSlowCallRtMillis(int llmSlowCallRtMillis) {
            this.llmSlowCallRtMillis = llmSlowCallRtMillis;
        }

        public double getExceptionRatio() {
            return exceptionRatio;
        }

        public void setExceptionRatio(double exceptionRatio) {
            this.exceptionRatio = exceptionRatio;
        }

        public int getCircuitBreakWindowSeconds() {
            return circuitBreakWindowSeconds;
        }

        public void setCircuitBreakWindowSeconds(int circuitBreakWindowSeconds) {
            this.circuitBreakWindowSeconds = circuitBreakWindowSeconds;
        }

        public int getMinRequestAmount() {
            return minRequestAmount;
        }

        public void setMinRequestAmount(int minRequestAmount) {
            this.minRequestAmount = minRequestAmount;
        }
    }

    /**
     * Agent（Plan-and-Solve + ReAct）安全边界与开关，对应项目书第六章第 4 节。
     */
    public static class Agent {
        private boolean enabled = true;
        private int maxToolCalls = 6;
        private int maxSubTasks = 5;
        private long maxRunMillis = 30000;
        private int maxReactStepsPerSubtask = 4;
        private int toolSearchTopK = 5;
        private boolean planningEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
        }

        public int getMaxSubTasks() {
            return maxSubTasks;
        }

        public void setMaxSubTasks(int maxSubTasks) {
            this.maxSubTasks = maxSubTasks;
        }

        public long getMaxRunMillis() {
            return maxRunMillis;
        }

        public void setMaxRunMillis(long maxRunMillis) {
            this.maxRunMillis = maxRunMillis;
        }

        public int getMaxReactStepsPerSubtask() {
            return maxReactStepsPerSubtask;
        }

        public void setMaxReactStepsPerSubtask(int maxReactStepsPerSubtask) {
            this.maxReactStepsPerSubtask = maxReactStepsPerSubtask;
        }

        public int getToolSearchTopK() {
            return toolSearchTopK;
        }

        public void setToolSearchTopK(int toolSearchTopK) {
            this.toolSearchTopK = toolSearchTopK;
        }

        public boolean isPlanningEnabled() {
            return planningEnabled;
        }

        public void setPlanningEnabled(boolean planningEnabled) {
            this.planningEnabled = planningEnabled;
        }
    }

    /**
     * 检索评测（nDCG@K / Recall@K / MRR）数据位置与回归门禁，对应项目书第四章。
     */
    public static class Eval {
        private String dataDir = "eval/data";
        private int[] ks = {5, 10};
        private int topN = 50;
        private double ndcgGate = 0.75;
        private double recallGate = 0.80;
        private double mrrGate = 0.70;

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }

        public int[] getKs() {
            return ks;
        }

        public void setKs(int[] ks) {
            this.ks = ks;
        }

        public int getTopN() {
            return topN;
        }

        public void setTopN(int topN) {
            this.topN = topN;
        }

        public double getNdcgGate() {
            return ndcgGate;
        }

        public void setNdcgGate(double ndcgGate) {
            this.ndcgGate = ndcgGate;
        }

        public double getRecallGate() {
            return recallGate;
        }

        public void setRecallGate(double recallGate) {
            this.recallGate = recallGate;
        }

        public double getMrrGate() {
            return mrrGate;
        }

        public void setMrrGate(double mrrGate) {
            this.mrrGate = mrrGate;
        }
    }

    /**
     * 语义重排序（Reranker）：在 Hybrid(RRF+MMR) 之后、ContextCompressor 之前，
     * 用 LLM 对候选片段按相关性 listwise 重排，提升 nDCG/MRR。失败或被限流时优雅降级回原排序。
     * 对应《重排序与多轮对话项目书》第三章。
     */
    public static class Rerank {
        /** 总开关。关闭时问答链路完全跳过重排序，等价于旧行为。 */
        private boolean enabled = true;
        /** 送入重排序的候选池大小（从 Hybrid 多取一些再重排，重排后裁剪到 finalTopK）。 */
        private int candidateK = 20;
        /** 每个候选片段进入重排序 prompt 的最大字符数，控制 token 成本。 */
        private int maxCandidateChars = 500;
        /** 重排序 LLM 调用的最大输出 token。 */
        private int maxOutputTokens = 512;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCandidateK() {
            return candidateK;
        }

        public void setCandidateK(int candidateK) {
            this.candidateK = candidateK;
        }

        public int getMaxCandidateChars() {
            return maxCandidateChars;
        }

        public void setMaxCandidateChars(int maxCandidateChars) {
            this.maxCandidateChars = maxCandidateChars;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    /**
     * 多轮对话与会话记忆，及其硬限制条件。对应《重排序与多轮对话项目书》第四章。
     * 记忆为内存存储（按 TTL 与容量上限自动淘汰），不落库，重启即清空。
     */
    public static class Conversation {
        /** 总开关。关闭时每次问答都是独立单轮，sessionId 被忽略。 */
        private boolean enabled = true;
        /** 是否基于历史对追问做 query 改写（指代消解），改写失败时回退原问题。 */
        private boolean rewriteEnabled = true;
        /** 单会话保留的最大历史轮数（超出丢弃最旧轮）。 */
        private int maxTurns = 6;
        /** 拼入改写/生成 prompt 的历史文本最大字符数（超出截断，保留最近轮）。 */
        private int maxHistoryChars = 4000;
        /** 单轮回答写入记忆前截断的最大字符数，防止单条记忆过大。 */
        private int maxStoredAnswerChars = 1000;
        /** 会话空闲过期时间（秒），超过未访问的会话在下次访问时被清除。 */
        private long sessionTtlSeconds = 1800;
        /** 进程内最多保留的会话数（超出淘汰最久未访问的会话），防止内存无界增长。 */
        private int maxSessions = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRewriteEnabled() {
            return rewriteEnabled;
        }

        public void setRewriteEnabled(boolean rewriteEnabled) {
            this.rewriteEnabled = rewriteEnabled;
        }

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }

        public int getMaxHistoryChars() {
            return maxHistoryChars;
        }

        public void setMaxHistoryChars(int maxHistoryChars) {
            this.maxHistoryChars = maxHistoryChars;
        }

        public int getMaxStoredAnswerChars() {
            return maxStoredAnswerChars;
        }

        public void setMaxStoredAnswerChars(int maxStoredAnswerChars) {
            this.maxStoredAnswerChars = maxStoredAnswerChars;
        }

        public long getSessionTtlSeconds() {
            return sessionTtlSeconds;
        }

        public void setSessionTtlSeconds(long sessionTtlSeconds) {
            this.sessionTtlSeconds = sessionTtlSeconds;
        }

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
        }
    }
}

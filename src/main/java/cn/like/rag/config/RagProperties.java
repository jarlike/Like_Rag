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
}

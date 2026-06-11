package cn.like.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private String storageRoot = "data/rag";
    private int chunkSize = 520;
    private int chunkOverlap = 80;
    private int chunkMaxTokens = 420;
    private int chunkOverlapTokens = 60;
    private int topK = 5;
    private int embeddingDimension = 384;
    private boolean asyncIndexFallback = true;
    private OpenAi openai = new OpenAi();
    private Mq mq = new Mq();

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
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
        private String baseUrl = "https://api.openai.com/v1";
        private String chatModel = "gpt-5.5";
        private String embeddingModel = "text-embedding-3-small";
        private int embeddingDimensions = 384;
        private int timeoutSeconds = 60;

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

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
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

package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingServiceTest {

    @Test
    void shouldFallbackToLocalEmbeddingWhenOpenAiIsDisabled() {
        RagProperties properties = new RagProperties();
        properties.getOpenai().setTimeoutSeconds(5);
        OpenAiClientService openAiClientService = new OpenAiClientService(
                properties,
                new RestTemplateBuilder().setConnectTimeout(Duration.ofSeconds(1)).setReadTimeout(Duration.ofSeconds(1)),
                new ObjectMapper());
        EmbeddingService embeddingService = new EmbeddingService(properties, openAiClientService);

        double[] sameTopic = embeddingService.embed("RocketMQ 索引消息");
        double[] otherTopic = embeddingService.embed("天气和菜谱");

        assertThat(sameTopic).hasSizeGreaterThan(0);
        assertThat(otherTopic).hasSize(sameTopic.length);
    }

    @Test
    void shouldFailWhenConfiguredOpenAiEmbeddingCannotBeGenerated() {
        RagProperties properties = new RagProperties();
        properties.getOpenai().setApiKey("test-key");
        properties.getOpenai().setBaseUrl("http://127.0.0.1:1/v1");
        properties.getOpenai().setEmbeddingEnabled(true);
        properties.getOpenai().setTimeoutSeconds(5);
        OpenAiClientService openAiClientService = new OpenAiClientService(
                properties,
                new RestTemplateBuilder().setConnectTimeout(Duration.ofMillis(200)).setReadTimeout(Duration.ofMillis(200)),
                new ObjectMapper());
        EmbeddingService embeddingService = new EmbeddingService(properties, openAiClientService);

        assertThatThrownBy(() -> embeddingService.embed("RocketMQ 索引消息"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAI embedding failed");
    }
}

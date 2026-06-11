package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

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
}

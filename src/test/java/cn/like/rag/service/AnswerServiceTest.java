package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.RagChunk;
import cn.like.rag.model.SearchHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerServiceTest {

    @Test
    void shouldNotFallbackToLocalAnswerWhenOpenAiIsConfiguredButFails() {
        RagProperties properties = new RagProperties();
        properties.getOpenai().setApiKey("test-key");
        properties.getOpenai().setBaseUrl("http://127.0.0.1:1/v1");
        properties.getOpenai().setTimeoutSeconds(5);

        OpenAiClientService openAiClientService = new OpenAiClientService(
                properties,
                new RestTemplateBuilder().setConnectTimeout(Duration.ofMillis(200)).setReadTimeout(Duration.ofMillis(200)),
                new ObjectMapper());
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);

        RagChunk chunk = new RagChunk();
        chunk.setDocumentId("doc-1");
        chunk.setDocumentName("test.md");
        chunk.setChunkIndex(0);
        chunk.setText("RocketMQ 用于发送索引任务。");
        chunk.setSectionPath("索引流程");
        when(vectorSearchService.search(anyString(), anyInt()))
                .thenReturn(List.of(new SearchHit(chunk, 0.92)));
        when(embeddingService.tokenize(anyString())).thenReturn(List.of("rocketmq"));

        AnswerService answerService = new AnswerService(
                vectorSearchService,
                embeddingService,
                openAiClientService,
                operationLogService);

        assertThatThrownBy(() -> answerService.answer("RocketMQ 如何参与索引？", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GPT-5.5 answer generation failed");
    }

    @Test
    void shouldRequireOpenAiConfigurationForChatAnswer() {
        RagProperties properties = new RagProperties();
        OpenAiClientService openAiClientService = new OpenAiClientService(
                properties,
                new RestTemplateBuilder().setConnectTimeout(Duration.ofMillis(200)).setReadTimeout(Duration.ofMillis(200)),
                new ObjectMapper());
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);

        AnswerService answerService = new AnswerService(
                vectorSearchService,
                embeddingService,
                openAiClientService,
                operationLogService);

        assertThatThrownBy(() -> answerService.answer("这个问题应该调用模型", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY is not configured");
    }
}

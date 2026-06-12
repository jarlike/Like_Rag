package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.RagChunk;
import cn.like.rag.model.SearchHit;
import cn.like.rag.sentinel.SentinelGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerServiceTest {

    /**
     * 构造一个"放行"的 SentinelGuard：3 参 call 直接执行 action（不限流），
     * 以便单测聚焦 force-GPT 策略本身，而非 Sentinel 行为。
     */
    @SuppressWarnings("unchecked")
    private SentinelGuard passthroughGuard() {
        SentinelGuard guard = mock(SentinelGuard.class);
        when(guard.call(anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());
        return guard;
    }

    private AnswerService answerService(RagProperties properties,
                                        OpenAiClientService openAiClientService,
                                        HybridSearchService hybridSearchService,
                                        RerankService rerankService,
                                        ContextCompressor contextCompressor,
                                        ConversationMemoryService conversationMemory,
                                        QueryRewriteService queryRewriteService,
                                        OperationLogService operationLogService) {
        return new AnswerService(
                hybridSearchService,
                rerankService,
                contextCompressor,
                openAiClientService,
                conversationMemory,
                queryRewriteService,
                operationLogService,
                passthroughGuard(),
                properties);
    }

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
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankService rerankService = mock(RerankService.class);
        ContextCompressor contextCompressor = mock(ContextCompressor.class);
        ConversationMemoryService conversationMemory = mock(ConversationMemoryService.class);
        QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);

        RagChunk chunk = new RagChunk();
        chunk.setDocumentId("doc-1");
        chunk.setDocumentName("test.md");
        chunk.setChunkIndex(0);
        chunk.setText("RocketMQ 用于发送索引任务。");
        chunk.setSectionPath("索引流程");
        List<SearchHit> hits = List.of(new SearchHit(chunk, 0.92));
        when(conversationMemory.historyText(any())).thenReturn("");
        when(hybridSearchService.search(anyString(), any())).thenReturn(hits);
        when(rerankService.rerank(anyString(), any(), anyInt())).thenReturn(hits);
        when(contextCompressor.compress(anyString(), any())).thenReturn(hits);

        AnswerService answerService = answerService(properties, openAiClientService, hybridSearchService,
                rerankService, contextCompressor, conversationMemory, queryRewriteService, operationLogService);

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
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankService rerankService = mock(RerankService.class);
        ContextCompressor contextCompressor = mock(ContextCompressor.class);
        ConversationMemoryService conversationMemory = mock(ConversationMemoryService.class);
        QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);

        AnswerService answerService = answerService(properties, openAiClientService, hybridSearchService,
                rerankService, contextCompressor, conversationMemory, queryRewriteService, operationLogService);

        assertThatThrownBy(() -> answerService.answer("这个问题应该调用模型", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY is not configured");
    }
}

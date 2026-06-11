package cn.like.rag.service;

import cn.like.rag.model.ChatResponse;
import cn.like.rag.model.SearchHit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AnswerService {

    private final VectorSearchService vectorSearchService;
    private final OpenAiClientService openAiClientService;
    private final OperationLogService operationLogService;

    public AnswerService(VectorSearchService vectorSearchService,
                         EmbeddingService embeddingService,
                         OpenAiClientService openAiClientService,
                         OperationLogService operationLogService) {
        this.vectorSearchService = vectorSearchService;
        this.openAiClientService = openAiClientService;
        this.operationLogService = operationLogService;
    }

    public ChatResponse answer(String question, Integer topK) {
        if (!openAiClientService.isConfigured()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured, cannot call GPT-5.5");
        }

        List<SearchHit> hits = vectorSearchService.search(question, topK);
        ChatResponse response = new ChatResponse();
        response.setQuestion(question);
        response.setProvider("openai");
        response.setModel(openAiClientService.chatModel());
        response.setHits(hits);
        response.setCitations(toCitations(hits));
        response.setAnswer(generateGptAnswer(question, hits));
        operationLogService.info("CHAT", "Question answered", null,
                Map.of(
                        "question", question,
                        "hitCount", hits.size(),
                        "provider", "openai",
                        "model", openAiClientService.chatModel()));
        return response;
    }

    private String generateGptAnswer(String question, List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "当前知识库没有检索到足够相关的内容，建议先上传相关文档或换一种问法。";
        }
        try {
            return openAiClientService.generateAnswer(question, hits);
        } catch (Exception e) {
            operationLogService.error("OPENAI_ANSWER_FAILED", "OpenAI answer generation failed", null,
                    Map.of("model", openAiClientService.chatModel(), "error", String.valueOf(e.getMessage())));
            throw new IllegalStateException("GPT-5.5 answer generation failed: " + e.getMessage(), e);
        }
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

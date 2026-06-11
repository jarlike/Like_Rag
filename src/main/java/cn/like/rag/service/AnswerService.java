package cn.like.rag.service;

import cn.like.rag.model.ChatResponse;
import cn.like.rag.model.SearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AnswerService {

    private final VectorSearchService vectorSearchService;
    private final EmbeddingService embeddingService;
    private final OpenAiClientService openAiClientService;
    private final OperationLogService operationLogService;

    public AnswerService(VectorSearchService vectorSearchService,
                         EmbeddingService embeddingService,
                         OpenAiClientService openAiClientService,
                         OperationLogService operationLogService) {
        this.vectorSearchService = vectorSearchService;
        this.embeddingService = embeddingService;
        this.openAiClientService = openAiClientService;
        this.operationLogService = operationLogService;
    }

    public ChatResponse answer(String question, Integer topK) {
        List<SearchHit> hits = vectorSearchService.search(question, topK);
        ChatResponse response = new ChatResponse();
        response.setQuestion(question);
        response.setHits(hits);
        response.setCitations(toCitations(hits));
        response.setAnswer(buildAnswer(question, hits));
        operationLogService.info("CHAT", "Question answered", null,
                Map.of("question", question, "hitCount", hits.size()));
        return response;
    }

    private String buildAnswer(String question, List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "当前知识库没有检索到足够相关的内容，建议先上传相关文档或换一种问法。";
        }
        if (openAiClientService.isConfigured()) {
            try {
                return openAiClientService.generateAnswer(question, hits);
            } catch (Exception e) {
                operationLogService.error("OPENAI_ANSWER_FAILED", "OpenAI answer generation failed", null,
                        Map.of("error", e.getMessage()));
            }
        }

        Set<String> questionTokens = new LinkedHashSet<>(embeddingService.tokenize(question));
        List<String> evidence = new ArrayList<>();
        for (SearchHit hit : hits) {
            evidence.addAll(bestSentences(hit.getChunk().getText(), questionTokens));
            if (evidence.size() >= 4) {
                break;
            }
        }
        if (evidence.isEmpty()) {
            evidence.add(snippet(hits.get(0).getChunk().getText(), 260));
        }

        StringBuilder answer = new StringBuilder();
        answer.append("根据已入库文档，可以这样回答：\n");
        for (int i = 0; i < evidence.size(); i++) {
            answer.append(i + 1).append(". ").append(evidence.get(i)).append("\n");
        }
        answer.append("以上内容来自检索命中的文档片段，具体出处见 citations。");
        return answer.toString().trim();
    }

    private List<String> bestSentences(String text, Set<String> questionTokens) {
        return splitSentences(text).stream()
                .map(sentence -> Map.entry(sentence, sentenceScore(sentence, questionTokens)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(2)
                .map(entry -> snippet(entry.getKey(), 220))
                .toList();
    }

    private List<String> splitSentences(String text) {
        String normalized = text == null ? "" : text.replace('\n', ' ');
        String[] parts = normalized.split("(?<=[。！？!?])\\s*");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String sentence = part.trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private double sentenceScore(String sentence, Set<String> questionTokens) {
        if (questionTokens.isEmpty()) {
            return sentence.isBlank() ? 0 : 0.1;
        }
        Set<String> sentenceTokens = new LinkedHashSet<>(embeddingService.tokenize(sentence.toLowerCase(Locale.ROOT)));
        int matched = 0;
        for (String token : questionTokens) {
            if (sentenceTokens.contains(token)) {
                matched++;
            }
        }
        return (double) matched / questionTokens.size();
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

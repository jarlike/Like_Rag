package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiClientService {

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public OpenAiClientService(RagProperties properties,
                               RestTemplateBuilder restTemplateBuilder,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        Duration timeout = Duration.ofSeconds(Math.max(5, properties.getOpenai().getTimeoutSeconds()));
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(timeout)
                .setReadTimeout(timeout)
                .build();
    }

    public boolean isConfigured() {
        String apiKey = properties.getOpenai().getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    public double[] embed(String text) {
        requireApiKey();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getOpenai().getEmbeddingModel());
        body.put("input", text == null ? "" : text);
        int dimensions = properties.getOpenai().getEmbeddingDimensions();
        if (dimensions > 0) {
            body.put("dimensions", dimensions);
        }

        JsonNode root = post("/embeddings", body);
        JsonNode embedding = root.path("data").path(0).path("embedding");
        if (!embedding.isArray()) {
            throw new IllegalStateException("OpenAI embedding response does not contain data[0].embedding");
        }
        double[] vector = new double[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i).asDouble();
        }
        return vector;
    }

    public String generateAnswer(String question, List<SearchHit> hits) {
        requireApiKey();
        String system = "You are a precise RAG answer generator.\n"
                + "Answer only from the provided context.\n"
                + "If the context is insufficient, say so directly.\n"
                + "Cite sources with bracket numbers like [1] that match the context blocks.\n"
                + "Keep the answer concise and in the same language as the question.";

        StringBuilder user = new StringBuilder();
        user.append("Question:\n").append(question).append("\n\n");
        user.append("Context:\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            user.append("[").append(i + 1).append("] ")
                    .append(hit.getChunk().getDocumentName())
                    .append(" / chunk ")
                    .append(hit.getChunk().getChunkIndex());
            if (hit.getChunk().getSectionPath() != null && !hit.getChunk().getSectionPath().isBlank()) {
                user.append(" / ").append(hit.getChunk().getSectionPath());
            }
            user.append("\n")
                    .append(hit.getChunk().getText())
                    .append("\n\n");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getOpenai().getChatModel());
        body.put("instructions", system);
        body.put("input", user.toString());
        body.put("max_output_tokens", 700);
        body.put("temperature", 0.2);

        JsonNode root = post("/responses", body);
        String outputText = extractOutputText(root);
        if (outputText.isBlank()) {
            throw new IllegalStateException("OpenAI response does not contain output text");
        }
        return outputText;
    }

    private JsonNode post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getOpenai().getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    baseUrl() + path,
                    HttpMethod.POST,
                    entity,
                    JsonNode.class);
            JsonNode responseBody = response.getBody();
            if (responseBody == null) {
                return objectMapper.createObjectNode();
            }
            return responseBody;
        } catch (RestClientException e) {
            throw new IllegalStateException("OpenAI request failed: " + e.getMessage(), e);
        }
    }

    private String extractOutputText(JsonNode root) {
        JsonNode outputText = root.path("output_text");
        if (outputText.isTextual()) {
            return outputText.asText().trim();
        }

        List<String> parts = new ArrayList<>();
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (JsonNode contentItem : content) {
                    JsonNode text = contentItem.path("text");
                    if (text.isTextual()) {
                        parts.add(text.asText());
                    }
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    private void requireApiKey() {
        if (!isConfigured()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured");
        }
    }

    private String baseUrl() {
        String baseUrl = properties.getOpenai().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}

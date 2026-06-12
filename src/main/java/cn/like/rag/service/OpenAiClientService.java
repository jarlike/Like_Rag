package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OpenAiClientService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClientService.class);

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

    public boolean isEmbeddingConfigured() {
        return isConfigured() && properties.getOpenai().isEmbeddingEnabled();
    }

    public String chatModel() {
        return properties.getOpenai().getChatModel();
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
        return generateAnswer(question, hits, null);
    }

    /**
     * 在 RAG 问答基础上可选注入多轮对话历史（仅用于理解追问/指代，不作为作答证据）。
     * conversationContext 为空时与单轮行为一致。
     */
    public String generateAnswer(String question, List<SearchHit> hits, String conversationContext) {
        requireApiKey();
        List<SearchHit> rankedHits = hits.stream()
                .sorted(Comparator.comparingDouble(SearchHit::getScore).reversed())
                .toList();

        boolean hasHistory = conversationContext != null && !conversationContext.isBlank();
        String system = "你是一个严格的 RAG 问答生成器。\n"
                + "只能使用用户提供的 Context 片段作答，不要使用外部知识或自由发挥。\n"
                + "Context 已按相似度从高到低排列，优先依据靠前且 score 更高的片段。\n"
                + (hasHistory ? "对话历史仅用于理解本轮追问的指代与省略，不能作为作答证据，证据只来自 Context。\n" : "")
                + "如果片段不足以回答，直接说明“检索到的文档片段不足以回答”。\n"
                + "回答必须和问题使用同一种语言。\n"
                + "每个关键结论后都要用 [1] 这种编号引用对应片段，编号必须和 Context 匹配。";

        StringBuilder user = new StringBuilder();
        if (hasHistory) {
            user.append("Conversation history (for reference only, not evidence):\n")
                    .append(conversationContext.trim()).append("\n\n");
        }
        user.append("Question:\n").append(question == null ? "" : question.trim()).append("\n\n");
        user.append("Context (ranked by semantic similarity, highest first):\n");
        for (int i = 0; i < rankedHits.size(); i++) {
            SearchHit hit = rankedHits.get(i);
            user.append("[").append(i + 1).append("] ")
                    .append("score=").append(String.format(Locale.ROOT, "%.4f", hit.getScore()))
                    .append(" | document=").append(hit.getChunk().getDocumentName())
                    .append(" | chunk=").append(hit.getChunk().getChunkIndex());
            if (hit.getChunk().getSectionPath() != null && !hit.getChunk().getSectionPath().isBlank()) {
                user.append(" | section=").append(hit.getChunk().getSectionPath());
            }
            user.append("\n")
                    .append(hit.getChunk().getText())
                    .append("\n\n");
        }

        String outputText;
        if (useResponsesEndpoint()) {
            outputText = generateWithResponses(system, user.toString(), 700);
        } else {
            outputText = generateWithChatCompletions(system, user.toString(), 700);
        }
        if (outputText.isBlank()) {
            throw new IllegalStateException("OpenAI response does not contain output text");
        }
        return outputText;
    }

    /**
     * 通用单轮补全：给定 system 指令与 user 输入，返回模型文本输出。
     * 供 Agent（Plan-and-Solve 计划生成 / ReAct 决策）复用，与 {@link #generateAnswer} 共享底层调用与重试。
     */
    public String complete(String system, String user, int maxOutputTokens) {
        requireApiKey();
        String outputText = useResponsesEndpoint()
                ? generateWithResponses(system, user, maxOutputTokens)
                : generateWithChatCompletions(system, user, maxOutputTokens);
        if (outputText.isBlank()) {
            throw new IllegalStateException("OpenAI response does not contain output text");
        }
        return outputText;
    }

    private String generateWithResponses(String system, String user, int maxOutputTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getOpenai().getChatModel());
        body.put("instructions", system);
        body.put("input", user);
        body.put("max_output_tokens", maxOutputTokens);

        JsonNode root = post("/responses", body);
        return extractResponsesOutputText(root);
    }

    private String generateWithChatCompletions(String system, String user, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getOpenai().getChatModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        ));
        body.put("max_tokens", maxTokens);

        JsonNode root = post("/chat/completions", body);
        return extractChatCompletionsOutputText(root);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getOpenai().getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        String url = baseUrl() + path;

        int maxRetries = Math.max(0, properties.getOpenai().getMaxRetries());
        int attempt = 0;
        while (true) {
            try {
                ResponseEntity<JsonNode> response = restTemplate.exchange(
                        url, HttpMethod.POST, entity, JsonNode.class);
                JsonNode responseBody = response.getBody();
                return responseBody == null ? objectMapper.createObjectNode() : responseBody;
            } catch (RestClientException e) {
                if (!isRetryable(e) || attempt >= maxRetries) {
                    throw new IllegalStateException("OpenAI request failed: " + e.getMessage(), e);
                }
                long backoff = properties.getOpenai().getRetryBackoffMillis() * (1L << attempt);
                log.warn("OpenAI request to {} failed (attempt {}/{}), retrying in {}ms: {}",
                        path, attempt + 1, maxRetries, backoff, e.getMessage());
                sleepBeforeRetry(backoff);
                attempt++;
            }
        }
    }

    private boolean isRetryable(RestClientException e) {
        if (e instanceof HttpStatusCodeException statusError) {
            int status = statusError.getRawStatusCode();
            // 429 限流、5xx 服务端临时故障（含中转站 503）值得重试；4xx（鉴权/请求错误）不重试
            return status == 429 || status >= 500;
        }
        // 连接/读取超时、网络抖动等瞬时 IO 问题
        return e instanceof ResourceAccessException;
    }

    private void sleepBeforeRetry(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI request retry interrupted", ie);
        }
    }

    private String extractResponsesOutputText(JsonNode root) {
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

    private String extractChatCompletionsOutputText(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode messageContent = choices.path(0).path("message").path("content");
        if (messageContent.isTextual()) {
            return messageContent.asText().trim();
        }
        if (!messageContent.isArray()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        for (JsonNode contentItem : messageContent) {
            JsonNode text = contentItem.path("text");
            if (text.isTextual()) {
                parts.add(text.asText());
            }
        }
        return String.join("\n", parts).trim();
    }

    private boolean useResponsesEndpoint() {
        String endpoint = properties.getOpenai().getChatEndpoint();
        return endpoint != null && "responses".equalsIgnoreCase(endpoint.trim());
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

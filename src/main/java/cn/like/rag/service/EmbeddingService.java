package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.util.PostgresTextSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final int dimension;
    private final OpenAiClientService openAiClientService;

    public EmbeddingService(RagProperties properties, OpenAiClientService openAiClientService) {
        this.dimension = Math.max(64, properties.getEmbeddingDimension());
        this.openAiClientService = openAiClientService;
    }

    public double[] embed(String text) {
        String cleaned = PostgresTextSanitizer.clean(text);
        if (openAiClientService.isConfigured()) {
            try {
                return openAiClientService.embed(cleaned);
            } catch (Exception e) {
                log.warn("OpenAI embedding failed, fallback to local embedding: {}", e.getMessage());
            }
        }
        return localEmbed(cleaned);
    }

    private double[] localEmbed(String text) {
        double[] vector = new double[dimension];
        for (String token : tokenize(text)) {
            int hash = murmurLikeHash(token);
            int index = Math.floorMod(hash, dimension);
            double sign = (hash & 1) == 0 ? 1.0 : -1.0;
            vector[index] += sign;
        }
        normalize(vector);
        return vector;
    }

    public List<String> tokenize(String text) {
        String normalized = PostgresTextSanitizer.clean(text);
        normalized = normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (isAsciiWord(ch)) {
                ascii.append(ch);
                continue;
            }
            flushAscii(tokens, ascii);
            if (Character.isLetterOrDigit(ch) || isCjk(ch)) {
                tokens.add(String.valueOf(ch));
                if (i + 1 < normalized.length()) {
                    char next = normalized.charAt(i + 1);
                    if (isCjk(ch) && isCjk(next)) {
                        tokens.add("" + ch + next);
                    }
                }
            }
        }
        flushAscii(tokens, ascii);
        return tokens;
    }

    public double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }

    private boolean isAsciiWord(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private void flushAscii(List<String> tokens, StringBuilder ascii) {
        if (ascii.length() == 0) {
            return;
        }
        String word = ascii.toString();
        tokens.add(word);
        for (int i = 0; i + 3 <= word.length(); i++) {
            tokens.add(word.substring(i, i + 3));
        }
        ascii.setLength(0);
    }

    private int murmurLikeHash(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        int hash = 0x9747b28c;
        for (byte b : bytes) {
            hash ^= b;
            hash *= 0x5bd1e995;
            hash ^= hash >>> 15;
        }
        return hash;
    }

    private void normalize(double[] vector) {
        double sum = 0;
        for (double value : vector) {
            sum += value * value;
        }
        if (sum == 0) {
            return;
        }
        double length = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / length;
        }
    }
}

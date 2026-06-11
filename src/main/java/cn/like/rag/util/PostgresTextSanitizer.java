package cn.like.rag.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PostgresTextSanitizer {

    private PostgresTextSanitizer() {
    }

    public static String clean(String value) {
        if (value == null || value.indexOf('\u0000') < 0) {
            return value;
        }
        return value.replace("\u0000", "");
    }

    public static String cleanAndLimit(String value, int maxLength) {
        String cleaned = clean(value);
        if (cleaned == null || maxLength <= 0 || cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    public static String errorMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return cleanAndLimit(message, 4000);
    }

    public static Map<String, String> cleanMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String cleanKey = clean(key);
            if (cleanKey != null && !cleanKey.isBlank()) {
                cleaned.put(cleanKey, clean(value));
            }
        });
        return cleaned;
    }
}

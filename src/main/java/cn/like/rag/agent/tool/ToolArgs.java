package cn.like.rag.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具入参解析助手：LLM 给出的 action_input 经 JSON 解析后类型不定（Integer/Double/String/List），
 * 这里做健壮的类型归一，避免单个坏参导致工具抛异常。
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    public static String asString(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    public static int asInt(Map<String, Object> args, String key, int defaultValue) {
        if (args == null) {
            return defaultValue;
        }
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignore) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public static List<String> asStringList(Map<String, Object> args, String key) {
        List<String> out = new ArrayList<>();
        if (args == null) {
            return out;
        }
        Object value = args.get(key);
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item).trim());
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,，\\s]+")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }
}

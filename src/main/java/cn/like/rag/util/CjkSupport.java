package cn.like.rag.util;

/**
 * 共享的 CJK 字符判定，供 Chunker、EmbeddingService 等使用，
 * 避免多份实现各自维护、彼此不一致（例如是否覆盖扩展 B 区）。
 */
public final class CjkSupport {

    private CjkSupport() {
    }

    public static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}

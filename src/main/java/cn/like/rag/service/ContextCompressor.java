package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.SearchHit;
import cn.like.rag.util.CjkSupport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文压缩：当命中片段总 token 超出预算时，按与问题的相关性做句子级裁剪，
 * 在不打乱原文顺序、不丢引用的前提下把进入大模型的上下文控制在 contextTokenBudget 内。
 * 直接裁剪本次查询命中片段的 text（本次专用对象，不影响库内数据）。
 */
@Service
public class ContextCompressor {

    private final EmbeddingService embeddingService;
    private final RagProperties properties;

    public ContextCompressor(EmbeddingService embeddingService, RagProperties properties) {
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    public List<SearchHit> compress(String question, List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return hits;
        }
        int budget = Math.max(0, properties.getContextTokenBudget() - properties.getReservedPromptTokens());
        int maxPerChunk = Math.max(1, properties.getMaxChunkContextTokens());

        int total = 0;
        for (SearchHit hit : hits) {
            total += estimateTokens(text(hit));
        }
        if (budget <= 0 || total <= budget) {
            return hits; // 未超预算，原样使用
        }

        Set<String> questionTokens = new LinkedHashSet<>(embeddingService.tokenize(question));
        List<SearchHit> kept = new ArrayList<>();
        int used = 0;
        for (SearchHit hit : hits) {
            if (used >= budget) {
                break; // 预算耗尽，丢弃低排名片段
            }
            int chunkBudget = Math.min(maxPerChunk, budget - used);
            String compressed = compressText(text(hit), questionTokens, chunkBudget);
            if (compressed.isBlank()) {
                continue;
            }
            if (hit.getChunk() != null) {
                hit.getChunk().setText(compressed);
            }
            used += estimateTokens(compressed);
            kept.add(hit);
        }
        return kept.isEmpty() ? hits.subList(0, 1) : kept;
    }

    private String text(SearchHit hit) {
        return hit.getChunk() == null ? "" : hit.getChunk().getText();
    }

    /**
     * 句子级压缩：按相关性挑选句子直到达到该 chunk 的 token 预算，再按原文顺序拼回。
     */
    private String compressText(String text, Set<String> questionTokens, int chunkBudget) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (estimateTokens(text) <= chunkBudget) {
            return text.trim();
        }
        List<String> sentences = splitSentences(text);
        List<Integer> byScore = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            byScore.add(i);
        }
        byScore.sort(Comparator.comparingDouble(
                (Integer i) -> sentenceScore(sentences.get(i), questionTokens)).reversed());

        Set<Integer> selected = new LinkedHashSet<>();
        int used = 0;
        for (int idx : byScore) {
            int t = estimateTokens(sentences.get(idx));
            if (used + t > chunkBudget && !selected.isEmpty()) {
                continue;
            }
            selected.add(idx);
            used += t;
            if (used >= chunkBudget) {
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (selected.contains(i)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(sentences.get(i));
            }
        }
        return sb.toString().trim();
    }

    private double sentenceScore(String sentence, Set<String> questionTokens) {
        if (questionTokens.isEmpty()) {
            return 0;
        }
        Set<String> tokens = new LinkedHashSet<>(embeddingService.tokenize(sentence));
        int matched = 0;
        for (String qt : questionTokens) {
            if (tokens.contains(qt)) {
                matched++;
            }
        }
        return (double) matched / questionTokens.size();
    }

    private List<String> splitSentences(String text) {
        String normalized = text.replace('\n', ' ');
        String[] parts = normalized.split("(?<=[。！？!?\\.])\\s*");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String s = part.trim();
            if (!s.isBlank()) {
                sentences.add(s);
            }
        }
        if (sentences.isEmpty()) {
            sentences.add(normalized.trim());
        }
        return sentences;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int tokens = 0;
        boolean inAsciiWord = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean ascii = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_';
            if (ascii) {
                if (!inAsciiWord) {
                    tokens++;
                    inAsciiWord = true;
                }
                continue;
            }
            inAsciiWord = false;
            if (CjkSupport.isCjk(ch) || Character.isLetterOrDigit(ch)) {
                tokens++;
            }
        }
        return Math.max(1, tokens);
    }
}

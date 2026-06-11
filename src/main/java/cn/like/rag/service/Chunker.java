package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.util.CjkSupport;
import cn.like.rag.util.PostgresTextSanitizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class Chunker {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^((\\d+\\.)+\\d*|\\d+)[\\s.、]+\\S.{0,80}$");
    private static final Pattern CHINESE_HEADING = Pattern.compile("^第[一二三四五六七八九十百千万0-9]+[章节篇部分].{0,80}$");
    private static final Pattern HTML_HEADING = Pattern.compile("(?is)<\\s*h([1-6])[^>]*>(.*?)</\\s*h\\1\\s*>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private final RagProperties properties;

    public Chunker(RagProperties properties) {
        this.properties = properties;
    }

    public List<String> split(String content) {
        return splitWithMetadata(content).stream()
                .map(ChunkDraft::text)
                .toList();
    }

    public List<ChunkDraft> splitWithMetadata(String content) {
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            return List.of();
        }

        int maxTokens = Math.max(8, properties.getChunkMaxTokens());
        int overlapTokens = Math.max(0, Math.min(properties.getChunkOverlapTokens(), maxTokens / 3));
        List<Block> blocks = toBlocks(normalized);
        List<ChunkDraft> chunks = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        MutableChunk current = new MutableChunk();

        for (Block block : blocks) {
            if (block.heading()) {
                flush(chunks, current);
                applyHeading(headingStack, block);
                continue;
            }

            String sectionPath = sectionPath(headingStack);
            for (String part : splitBlockByTokenLimit(block.text(), maxTokens)) {
                int partTokens = estimateTokens(part);
                if (!current.isEmpty() && current.tokenCount() + partTokens > maxTokens) {
                    String overlap = tailByTokenBudget(current.text(), overlapTokens);
                    flush(chunks, current);
                    current.setSectionPath(sectionPath);
                    if (!overlap.isBlank()) {
                        current.add(overlap, estimateTokens(overlap));
                    }
                } else if (current.isEmpty()) {
                    current.setSectionPath(sectionPath);
                }
                current.add(part, partTokens);
            }
        }

        flush(chunks, current);
        return chunks;
    }

    private String normalize(String content) {
        String cleaned = PostgresTextSanitizer.clean(content);
        if (cleaned == null) {
            return "";
        }
        String normalized = convertHtmlHeadings(cleaned)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("(?i)<\\s*br\\s*/?\\s*>", "\n")
                .replaceAll("(?i)</\\s*(p|div|section|article|header|footer|li|h[1-6]|tr)\\s*>", "\n")
                .replaceAll("(?i)<\\s*(p|div|section|article|header|footer|li|h[1-6]|tr)[^>]*>", "\n")
                .replaceAll("(?i)</\\s*(td|th)\\s*>", " ")
                .replaceAll("(?i)<\\s*(td|th)[^>]*>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&amp;", "&");
        normalized = HTML_TAG.matcher(normalized).replaceAll(" ");
        return normalized
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String convertHtmlHeadings(String text) {
        Matcher matcher = HTML_HEADING.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            int level = Integer.parseInt(matcher.group(1));
            String headingText = HTML_TAG.matcher(matcher.group(2)).replaceAll(" ").trim();
            String replacement = "\n" + "#".repeat(level) + " " + headingText + "\n";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private List<Block> toBlocks(String content) {
        List<Block> blocks = new ArrayList<>();
        String[] paragraphs = content.split("\\n\\s*\\n");
        for (String paragraph : paragraphs) {
            List<String> lines = paragraph.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();
            if (lines.isEmpty()) {
                continue;
            }

            if (lines.size() == 1 && isHeading(lines.get(0))) {
                blocks.add(new Block(lines.get(0), true, headingLevel(lines.get(0))));
                continue;
            }

            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                if (isHeading(line)) {
                    if (!text.toString().isBlank()) {
                        blocks.add(new Block(text.toString().trim(), false, 0));
                        text.setLength(0);
                    }
                    blocks.add(new Block(line, true, headingLevel(line)));
                } else {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(line);
                }
            }
            if (!text.toString().isBlank()) {
                blocks.add(new Block(text.toString().trim(), false, 0));
            }
        }
        return blocks;
    }

    private boolean isHeading(String line) {
        String trimmed = line.trim();
        if (trimmed.length() > 120) {
            return false;
        }
        if (!MARKDOWN_HEADING.matcher(trimmed).matches()
                && trimmed.matches(".*[。！？!?，,；;].*")) {
            return false;
        }
        return MARKDOWN_HEADING.matcher(trimmed).matches()
                || CHINESE_HEADING.matcher(trimmed).matches()
                || NUMBERED_HEADING.matcher(trimmed).matches();
    }

    private int headingLevel(String line) {
        Matcher markdown = MARKDOWN_HEADING.matcher(line);
        if (markdown.matches()) {
            return markdown.group(1).length();
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("第") && (trimmed.contains("章") || trimmed.contains("篇"))) {
            return 1;
        }
        if (trimmed.startsWith("第") && (trimmed.contains("节") || trimmed.contains("部分"))) {
            return 2;
        }
        int dots = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '.') {
                dots++;
            } else if (!Character.isDigit(trimmed.charAt(i))) {
                break;
            }
        }
        return Math.max(1, Math.min(6, dots + 1));
    }

    private void applyHeading(List<String> headingStack, Block heading) {
        int level = Math.max(1, heading.level());
        while (headingStack.size() >= level) {
            headingStack.remove(headingStack.size() - 1);
        }
        headingStack.add(cleanHeading(heading.text()));
    }

    private String cleanHeading(String text) {
        Matcher markdown = MARKDOWN_HEADING.matcher(text.trim());
        if (markdown.matches()) {
            return markdown.group(2).trim();
        }
        return text.trim();
    }

    private String sectionPath(List<String> headingStack) {
        return String.join(" > ", headingStack);
    }

    private List<String> splitBlockByTokenLimit(String block, int maxTokens) {
        if (estimateTokens(block) <= maxTokens) {
            return List.of(block);
        }

        List<String> parts = new ArrayList<>();
        MutableChunk current = new MutableChunk();
        for (String sentence : splitSentenceLikeUnits(block)) {
            int sentenceTokens = estimateTokens(sentence);
            if (sentenceTokens > maxTokens) {
                flushText(parts, current);
                parts.addAll(splitLongText(sentence, maxTokens));
                continue;
            }
            if (!current.isEmpty() && current.tokenCount() + sentenceTokens > maxTokens) {
                flushText(parts, current);
            }
            current.add(sentence, sentenceTokens);
        }
        flushText(parts, current);
        return parts;
    }

    private List<String> splitSentenceLikeUnits(String text) {
        String normalized = text == null ? "" : text.replace('\n', ' ');
        List<String> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            current.append(ch);
            if (isSentenceBoundary(ch)) {
                addUnit(units, current);
            }
        }
        addUnit(units, current);
        return units;
    }

    private List<String> splitSentences(String text) {
        String normalized = text == null ? "" : text;
        String[] candidates = normalized.split("(?<=[。！？!?\\.])\\s+|\\n+");
        List<String> sentences = new ArrayList<>();
        for (String candidate : candidates) {
            String sentence = candidate.trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty() && !normalized.isBlank()) {
            sentences.add(normalized.trim());
        }
        return sentences;
    }

    private List<String> splitLongText(String text, int maxTokens) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int tokens = 0;
        int lastSoftBreak = -1;
        int tokensAtSoftBreak = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            current.append(ch);
            tokens += estimateCharTokens(ch);
            if (isSoftBoundary(ch)) {
                lastSoftBreak = current.length();
                tokensAtSoftBreak = tokens;
            }
            if (tokens >= maxTokens) {
                if (lastSoftBreak > 0 && current.length() - lastSoftBreak < Math.min(80, maxTokens)) {
                    parts.add(current.substring(0, lastSoftBreak).trim());
                    String remainder = current.substring(lastSoftBreak).trim();
                    current.setLength(0);
                    current.append(remainder);
                    tokens = Math.max(0, tokens - tokensAtSoftBreak);
                } else {
                    parts.add(current.toString().trim());
                    current.setLength(0);
                    tokens = 0;
                }
                lastSoftBreak = -1;
                tokensAtSoftBreak = 0;
            }
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    private String tailByTokenBudget(String text, int tokenBudget) {
        if (tokenBudget <= 0 || text == null || text.isBlank()) {
            return "";
        }
        List<String> sentences = splitSentences(text);
        StringBuilder tail = new StringBuilder();
        int tokens = 0;
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i);
            int sentenceTokens = estimateTokens(sentence);
            if (tokens > 0 && tokens + sentenceTokens > tokenBudget) {
                break;
            }
            if (tail.length() > 0) {
                tail.insert(0, "\n");
            }
            tail.insert(0, sentence);
            tokens += sentenceTokens;
            if (tokens >= tokenBudget) {
                break;
            }
        }
        return tail.toString().trim();
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int tokens = 0;
        boolean inAsciiWord = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isAsciiWord(ch)) {
                if (!inAsciiWord) {
                    tokens++;
                    inAsciiWord = true;
                }
                continue;
            }
            inAsciiWord = false;
            tokens += estimateCharTokens(ch);
        }
        return Math.max(1, tokens);
    }

    private int estimateCharTokens(char ch) {
        if (Character.isWhitespace(ch)) {
            return 0;
        }
        if (CjkSupport.isCjk(ch)) {
            return 1;
        }
        if (Character.isLetterOrDigit(ch)) {
            return 1;
        }
        return 0;
    }

    private boolean isAsciiWord(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '_';
    }

    private void flush(List<ChunkDraft> chunks, MutableChunk current) {
        String text = current.text().trim();
        if (!text.isBlank()) {
            chunks.add(new ChunkDraft(text, current.sectionPath(), current.tokenCount()));
        }
        current.clear();
    }

    private void flushText(List<String> parts, MutableChunk current) {
        String text = current.text().trim();
        if (!text.isBlank()) {
            parts.add(text);
        }
        current.clear();
    }

    public record ChunkDraft(String text, String sectionPath, int tokenCount) {
    }

    private record Block(String text, boolean heading, int level) {
    }

    private static class MutableChunk {
        private final StringBuilder text = new StringBuilder();
        private String sectionPath = "";
        private int tokenCount = 0;

        void add(String part, int partTokens) {
            if (part == null || part.isBlank()) {
                return;
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(part.trim());
            tokenCount += Math.max(0, partTokens);
        }

        void setSectionPath(String sectionPath) {
            this.sectionPath = sectionPath == null ? "" : sectionPath;
        }

        String sectionPath() {
            return sectionPath;
        }

        int tokenCount() {
            return tokenCount;
        }

        String text() {
            return text.toString();
        }

        boolean isEmpty() {
            return text.toString().isBlank();
        }

        void clear() {
            text.setLength(0);
            sectionPath = "";
            tokenCount = 0;
        }
    }

    private boolean isSentenceBoundary(char ch) {
        return ch == '。'
                || ch == '！'
                || ch == '？'
                || ch == '!'
                || ch == '?'
                || ch == '.';
    }

    private boolean isSoftBoundary(char ch) {
        return Character.isWhitespace(ch)
                || ch == '，'
                || ch == ','
                || ch == '；'
                || ch == ';'
                || ch == '、'
                || isSentenceBoundary(ch);
    }

    private void addUnit(List<String> units, StringBuilder current) {
        String unit = current.toString().trim();
        if (!unit.isBlank()) {
            units.add(unit);
        }
        current.setLength(0);
    }
}

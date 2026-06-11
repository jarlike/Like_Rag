package cn.like.rag.service.parser;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName, String contentType) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".txt")
                || lowerName.endsWith(".md")
                || lowerName.endsWith(".markdown")
                || (contentType != null && contentType.startsWith("text/"));
    }

    @Override
    public String parse(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse text document: " + path, e);
        }
    }
}

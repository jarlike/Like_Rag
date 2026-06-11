package cn.like.rag.service.parser;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public DocumentParser getParser(String fileName, String contentType) {
        return parsers.stream()
                .filter(parser -> parser.supports(fileName, contentType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported document type: " + fileName));
    }
}

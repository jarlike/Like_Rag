package cn.like.rag.service.parser;

import java.nio.file.Path;

public interface DocumentParser {

    boolean supports(String fileName, String contentType);

    String parse(Path path);
}

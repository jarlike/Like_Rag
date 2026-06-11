package cn.like.rag.service.parser;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Component
public class DocxDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName, String contentType) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".docx")
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(contentType);
    }

    @Override
    public String parse(Path path) {
        try (InputStream inputStream = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse docx document: " + path, e);
        }
    }
}

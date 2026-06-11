package cn.like.rag.service.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName, String contentType) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(contentType);
    }

    @Override
    public String parse(Path path) {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse PDF document: " + path, e);
        }
    }
}

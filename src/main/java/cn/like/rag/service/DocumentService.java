package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.DocumentStatus;
import cn.like.rag.model.IndexMessage;
import cn.like.rag.model.RagDocument;
import cn.like.rag.repository.ChunkRepository;
import cn.like.rag.repository.DocumentRepository;
import cn.like.rag.service.mq.IndexTaskPublisher;
import cn.like.rag.util.PostgresTextSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private final RagProperties properties;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final IndexTaskPublisher indexTaskPublisher;
    private final OperationLogService operationLogService;

    public DocumentService(RagProperties properties,
                           DocumentRepository documentRepository,
                           ChunkRepository chunkRepository,
                           IndexTaskPublisher indexTaskPublisher,
                           OperationLogService operationLogService) {
        this.properties = properties;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.indexTaskPublisher = indexTaskPublisher;
        this.operationLogService = operationLogService;
    }

    public RagDocument upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String id = UUID.randomUUID().toString();
        String originalName = sanitizeFileName(file.getOriginalFilename());
        Path uploadDir = Path.of(properties.getStorageRoot(), "uploads", id);
        Path target = uploadDir.resolve(originalName);
        try {
            Files.createDirectories(uploadDir);
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save uploaded file", e);
        }

        LocalDateTime now = LocalDateTime.now();
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setFileName(originalName);
        document.setContentType(PostgresTextSanitizer.clean(file.getContentType()));
        document.setSize(file.getSize());
        document.setStoragePath(target.toString());
        document.setStatus(DocumentStatus.UPLOADED);
        document.setChunkCount(0);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentRepository.save(document);
        operationLogService.info("UPLOAD", "Document uploaded", id, Map.of("fileName", originalName, "size", file.getSize()));

        indexTaskPublisher.publish(new IndexMessage(id, false));
        return document;
    }

    public void rebuild(String documentId) {
        RagDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        document.setStatus(DocumentStatus.UPLOADED);
        document.setErrorMessage(null);
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(document);
        chunkRepository.deleteByDocumentId(documentId);
        operationLogService.info("REINDEX_REQUEST", "Document reindex requested", documentId, Map.of("fileName", document.getFileName()));
        indexTaskPublisher.publish(new IndexMessage(documentId, true));
    }

    public List<RagDocument> list() {
        return documentRepository.findAll();
    }

    public List<cn.like.rag.model.RagChunk> chunks(String documentId) {
        return chunkRepository.findByDocumentId(documentId);
    }

    private String sanitizeFileName(String originalFilename) {
        String fallback = "document.txt";
        if (originalFilename == null || originalFilename.isBlank()) {
            return fallback;
        }
        String clean = Path.of(PostgresTextSanitizer.clean(originalFilename)).getFileName().toString();
        clean = clean.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return clean.isBlank() ? fallback : clean;
    }
}

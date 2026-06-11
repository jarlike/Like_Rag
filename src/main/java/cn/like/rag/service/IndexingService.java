package cn.like.rag.service;

import cn.like.rag.model.DocumentStatus;
import cn.like.rag.model.RagChunk;
import cn.like.rag.model.RagDocument;
import cn.like.rag.repository.ChunkRepository;
import cn.like.rag.repository.DocumentRepository;
import cn.like.rag.service.parser.DocumentParser;
import cn.like.rag.service.parser.DocumentParserRegistry;
import cn.like.rag.util.PostgresTextSanitizer;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IndexingService {

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentParserRegistry parserRegistry;
    private final Chunker chunker;
    private final EmbeddingService embeddingService;
    private final OperationLogService operationLogService;

    public IndexingService(DocumentRepository documentRepository,
                           ChunkRepository chunkRepository,
                           DocumentParserRegistry parserRegistry,
                           Chunker chunker,
                           EmbeddingService embeddingService,
                           OperationLogService operationLogService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.parserRegistry = parserRegistry;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.operationLogService = operationLogService;
    }

    public void indexDocument(String documentId, boolean rebuild) {
        RagDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        try {
            mark(document, DocumentStatus.INDEXING, null);
            operationLogService.info("INDEX_START", "Start indexing document", documentId, Map.of("rebuild", rebuild));

            DocumentParser parser = parserRegistry.getParser(document.getFileName(), document.getContentType());
            String text = PostgresTextSanitizer.clean(parser.parse(Path.of(document.getStoragePath())));
            List<Chunker.ChunkDraft> rawChunks = chunker.splitWithMetadata(text);
            List<RagChunk> chunks = createChunks(document, rawChunks);
            chunkRepository.replaceDocumentChunks(documentId, chunks);

            document.setChunkCount(chunks.size());
            document.setIndexedAt(LocalDateTime.now());
            mark(document, DocumentStatus.INDEXED, null);
            operationLogService.info("INDEX_DONE", "Document indexed", documentId, Map.of("chunkCount", chunks.size()));
        } catch (Exception e) {
            chunkRepository.deleteByDocumentId(documentId);
            String errorMessage = PostgresTextSanitizer.errorMessage(e);
            mark(document, DocumentStatus.FAILED, errorMessage);
            operationLogService.error("INDEX_FAILED", "Document indexing failed", documentId, Map.of("error", errorMessage));
            throw e;
        }
    }

    private List<RagChunk> createChunks(RagDocument document, List<Chunker.ChunkDraft> rawChunks) {
        LocalDateTime now = LocalDateTime.now();
        return java.util.stream.IntStream.range(0, rawChunks.size())
                .mapToObj(index -> {
                    Chunker.ChunkDraft draft = rawChunks.get(index);
                    String text = PostgresTextSanitizer.clean(draft.text());
                    String sectionPath = PostgresTextSanitizer.clean(draft.sectionPath());
                    RagChunk chunk = new RagChunk();
                    chunk.setId(UUID.randomUUID().toString());
                    chunk.setDocumentId(document.getId());
                    chunk.setDocumentName(PostgresTextSanitizer.clean(document.getFileName()));
                    chunk.setChunkIndex(index);
                    chunk.setText(text);
                    chunk.setVector(embeddingService.embed(text));
                    chunk.setSectionPath(sectionPath);
                    chunk.setSearchText(embeddingService.buildSearchText(
                            text, sectionPath, PostgresTextSanitizer.clean(document.getFileName())));
                    chunk.setTokenCount(draft.tokenCount());
                    chunk.setCreatedAt(now);
                    Map<String, String> metadata = new HashMap<>();
                    metadata.put("fileName", PostgresTextSanitizer.clean(document.getFileName()));
                    metadata.put("contentType", PostgresTextSanitizer.clean(document.getContentType()));
                    metadata.put("source", PostgresTextSanitizer.clean(document.getStoragePath()));
                    metadata.put("sectionPath", sectionPath);
                    metadata.put("tokenCount", String.valueOf(draft.tokenCount()));
                    chunk.setMetadata(metadata);
                    return chunk;
                })
                .toList();
    }

    private void mark(RagDocument document, DocumentStatus status, String errorMessage) {
        document.setStatus(status);
        document.setErrorMessage(PostgresTextSanitizer.cleanAndLimit(errorMessage, 4000));
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(document);
    }
}

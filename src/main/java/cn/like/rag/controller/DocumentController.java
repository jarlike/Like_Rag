package cn.like.rag.controller;

import cn.like.rag.model.RagChunk;
import cn.like.rag.model.RagDocument;
import cn.like.rag.sentinel.SentinelGuard;
import cn.like.rag.sentinel.SentinelResources;
import cn.like.rag.service.DocumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final SentinelGuard sentinelGuard;

    public DocumentController(DocumentService documentService, SentinelGuard sentinelGuard) {
        this.documentService = documentService;
        this.sentinelGuard = sentinelGuard;
    }

    @PostMapping
    public RagDocument upload(@RequestParam("file") MultipartFile file) {
        return sentinelGuard.call(SentinelResources.API_UPLOAD, () -> documentService.upload(file));
    }

    @GetMapping
    public List<RagDocument> list() {
        return documentService.list();
    }

    @PostMapping("/{documentId}/reindex")
    public void reindex(@PathVariable String documentId) {
        sentinelGuard.run(SentinelResources.API_REINDEX, () -> documentService.rebuild(documentId));
    }

    @GetMapping("/{documentId}/chunks")
    public List<RagChunk> chunks(@PathVariable String documentId) {
        return documentService.chunks(documentId);
    }
}

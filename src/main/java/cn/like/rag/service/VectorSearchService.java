package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.SearchHit;
import cn.like.rag.repository.ChunkRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VectorSearchService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final RagProperties properties;

    public VectorSearchService(ChunkRepository chunkRepository, EmbeddingService embeddingService, RagProperties properties) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    public List<SearchHit> search(String query, Integer topK) {
        int limit = topK == null ? properties.getTopK() : Math.max(1, topK);
        double[] queryVector = embeddingService.embed(query);
        int candidateLimit = limit * 4;
        return chunkRepository.search(queryVector, candidateLimit)
                .stream()
                .limit(limit)
                .toList();
    }
}

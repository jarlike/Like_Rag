package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.RagChunk;
import cn.like.rag.model.SearchHit;
import cn.like.rag.repository.ChunkRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        Set<String> queryTokens = new HashSet<>(embeddingService.tokenize(query));
        int candidateLimit = Math.max(limit, limit * 4);
        return chunkRepository.search(queryVector, candidateLimit)
                .stream()
                .map(hit -> new SearchHit(hit.getChunk(), rerank(hit, queryTokens)))
                .sorted(Comparator.comparingDouble(SearchHit::getScore).reversed())
                .limit(limit)
                .toList();
    }

    private double rerank(SearchHit hit, Set<String> queryTokens) {
        RagChunk chunk = hit.getChunk();
        double vectorScore = Math.max(0, hit.getScore());
        double keywordScore = keywordOverlap(queryTokens, chunk.getText());
        return vectorScore * 0.85 + keywordScore * 0.15;
    }

    private double keywordOverlap(Set<String> queryTokens, String text) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        Set<String> chunkTokens = new HashSet<>(embeddingService.tokenize(text));
        int matched = 0;
        for (String token : queryTokens) {
            if (chunkTokens.contains(token)) {
                matched++;
            }
        }
        return (double) matched / Math.max(1, queryTokens.size());
    }
}

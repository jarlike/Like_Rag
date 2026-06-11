package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.SearchHit;
import cn.like.rag.repository.ChunkRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 混合检索：dense Top-N + sparse Top-N → RRF 融合 → MMR 去重 → Top finalK。
 * 项目书第二阶段核心入口，供问答与调试统一使用。
 */
@Service
public class HybridSearchService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final RrfFusionService rrfFusionService;
    private final MmrDedupService mmrDedupService;
    private final RagProperties properties;

    public HybridSearchService(ChunkRepository chunkRepository,
                               EmbeddingService embeddingService,
                               RrfFusionService rrfFusionService,
                               MmrDedupService mmrDedupService,
                               RagProperties properties) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.rrfFusionService = rrfFusionService;
        this.mmrDedupService = mmrDedupService;
        this.properties = properties;
    }

    public List<SearchHit> search(String query, Integer topK) {
        int finalK = topK == null ? properties.getFinalTopK() : Math.max(1, topK);

        double[] queryVector = embeddingService.embed(query);
        List<SearchHit> dense = chunkRepository.search(queryVector, properties.getDenseTopK());

        String tsQuery = embeddingService.toTsQuery(query);
        List<SearchHit> sparse = tsQuery.isEmpty()
                ? List.of()
                : chunkRepository.searchSparse(tsQuery, properties.getSparseTopK());

        List<SearchHit> fused = rrfFusionService.fuse(dense, sparse);
        return mmrDedupService.select(fused, finalK);
    }
}

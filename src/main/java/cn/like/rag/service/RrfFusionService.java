package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.RagChunk;
import cn.like.rag.model.SearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：把稀疏/密集两路召回按"排名"融合，规避两者分数尺度不可比的问题。
 * rrf(d) = Σ 1 / (k + rank_i(d))，rank 从 1 计，k 为平滑参数（默认 60）。
 */
@Service
public class RrfFusionService {

    private final RagProperties properties;

    public RrfFusionService(RagProperties properties) {
        this.properties = properties;
    }

    public List<SearchHit> fuse(List<SearchHit> dense, List<SearchHit> sparse) {
        int k = Math.max(1, properties.getRrfK());
        Map<String, SearchHit> byId = new LinkedHashMap<>();

        accumulate(byId, dense, k, true);
        accumulate(byId, sparse, k, false);

        List<SearchHit> fused = new ArrayList<>(byId.values());
        fused.sort(Comparator.comparingDouble(SearchHit::getRrfScore).reversed());
        return fused;
    }

    private void accumulate(Map<String, SearchHit> byId, List<SearchHit> hits, int k, boolean dense) {
        if (hits == null) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            RagChunk chunk = hit.getChunk();
            if (chunk == null || chunk.getId() == null) {
                continue;
            }
            int rank = i + 1;
            double contribution = 1.0 / (k + rank);
            SearchHit merged = byId.computeIfAbsent(chunk.getId(), id -> new SearchHit(chunk, 0.0));
            if (dense) {
                merged.setDenseRank(rank);
                merged.setDenseScore(hit.getScore());
            } else {
                merged.setSparseRank(rank);
                merged.setSparseScore(hit.getScore());
            }
            merged.setRrfScore(merged.getRrfScore() + contribution);
            merged.setScore(merged.getRrfScore());
        }
    }
}

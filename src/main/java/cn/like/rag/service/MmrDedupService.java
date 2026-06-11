package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.SearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Maximal Marginal Relevance：在相关性(rrfScore)与多样性之间取平衡，并用向量余弦剔除近重复。
 * MMR(d) = λ * relevance(d) - (1-λ) * max sim(d, selected)；sim >= duplicateThreshold 视为近重复跳过。
 * 输入应为已按相关性降序的候选，输出最多 finalK 个。
 */
@Service
public class MmrDedupService {

    private final RagProperties properties;

    public MmrDedupService(RagProperties properties) {
        this.properties = properties;
    }

    public List<SearchHit> select(List<SearchHit> candidates, int finalK) {
        if (candidates == null || candidates.isEmpty() || finalK <= 0) {
            return List.of();
        }
        double lambda = properties.getMmrLambda();
        double dupThreshold = properties.getDuplicateThreshold();

        List<SearchHit> remaining = new ArrayList<>(candidates);
        List<SearchHit> selected = new ArrayList<>();

        // 以 rrfScore 最大值归一化相关性，避免与余弦相似度量纲悬殊导致 λ 失衡
        double maxRel = remaining.stream().mapToDouble(SearchHit::getRrfScore).max().orElse(0.0);
        double relNorm = maxRel > 0 ? maxRel : 1.0;

        while (!remaining.isEmpty() && selected.size() < finalK) {
            SearchHit best = null;
            double bestScore = -Double.MAX_VALUE;
            for (SearchHit cand : remaining) {
                double maxSim = maxSimilarityToSelected(cand, selected);
                if (maxSim >= dupThreshold) {
                    continue; // 近重复，直接丢弃
                }
                double mmr = lambda * (cand.getRrfScore() / relNorm) - (1 - lambda) * maxSim;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    best = cand;
                }
            }
            if (best == null) {
                break; // 剩余候选全是近重复
            }
            selected.add(best);
            remaining.remove(best);
        }
        return selected;
    }

    private double maxSimilarityToSelected(SearchHit candidate, List<SearchHit> selected) {
        double max = 0.0;
        double[] candVector = candidate.getChunk() == null ? null : candidate.getChunk().getVector();
        for (SearchHit s : selected) {
            double[] selVector = s.getChunk() == null ? null : s.getChunk().getVector();
            double sim = cosine(candVector, selVector);
            if (sim > max) {
                max = sim;
            }
        }
        return max;
    }

    private double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length != a.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}

package cn.like.rag.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 检索质量指标，对应项目书第四章第 2 节：nDCG@K / Recall@K / MRR。
 *
 * <p>nDCG 采用指数增益（与项目书公式一致）：
 * <pre>
 *   DCG@K  = Σ (2^rel_i - 1) / log2(rank_i + 1)     // rank 从 1 开始
 *   IDCG@K = 理想排序下的 DCG@K
 *   nDCG@K = DCG@K / IDCG@K
 * </pre>
 * 相关性等级 rel ∈ {0,1,2,3}。本类为纯函数实现，便于单元测试与复用。
 */
public final class RetrievalMetrics {

    private static final double LOG2 = Math.log(2);

    private RetrievalMetrics() {
    }

    /** 指数增益 DCG：grades 按排名顺序给出（index 0 = rank 1）。 */
    public static double dcg(List<Integer> grades) {
        double sum = 0.0;
        for (int i = 0; i < grades.size(); i++) {
            int rel = grades.get(i) == null ? 0 : grades.get(i);
            if (rel <= 0) {
                continue;
            }
            double gain = Math.pow(2, rel) - 1;
            double discount = Math.log(i + 2) / LOG2; // log2(rank+1), rank = i+1
            sum += gain / discount;
        }
        return sum;
    }

    /** nDCG@K：rankedIds 为检索结果（按相关性从高到低），qrels 为 id→相关性等级。 */
    public static double ndcgAtK(List<String> rankedIds, Map<String, Integer> qrels, int k) {
        if (rankedIds == null || qrels == null || qrels.isEmpty() || k <= 0) {
            return 0.0;
        }
        List<Integer> gains = new ArrayList<>();
        int limit = Math.min(k, rankedIds.size());
        for (int i = 0; i < limit; i++) {
            gains.add(qrels.getOrDefault(rankedIds.get(i), 0));
        }
        double dcg = dcg(gains);

        List<Integer> ideal = qrels.values().stream()
                .sorted(Comparator.reverseOrder())
                .limit(k)
                .toList();
        double idcg = dcg(ideal);
        return idcg > 0 ? dcg / idcg : 0.0;
    }

    /** Recall@K：TopK 命中的相关文档数 / 全部相关文档数（相关 = grade>0）。 */
    public static double recallAtK(List<String> rankedIds, Map<String, Integer> qrels, int k) {
        if (rankedIds == null || qrels == null || k <= 0) {
            return 0.0;
        }
        long relevantTotal = qrels.values().stream().filter(g -> g != null && g > 0).count();
        if (relevantTotal == 0) {
            return 0.0;
        }
        int limit = Math.min(k, rankedIds.size());
        long hit = 0;
        for (int i = 0; i < limit; i++) {
            Integer grade = qrels.get(rankedIds.get(i));
            if (grade != null && grade > 0) {
                hit++;
            }
        }
        return (double) hit / relevantTotal;
    }

    /** MRR：第一个相关结果（grade>0）的位置倒数；无命中返回 0。 */
    public static double mrr(List<String> rankedIds, Map<String, Integer> qrels) {
        if (rankedIds == null || qrels == null) {
            return 0.0;
        }
        for (int i = 0; i < rankedIds.size(); i++) {
            Integer grade = qrels.get(rankedIds.get(i));
            if (grade != null && grade > 0) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}

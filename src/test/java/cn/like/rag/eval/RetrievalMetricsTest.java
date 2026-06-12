package cn.like.rag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RetrievalMetricsTest {

    @Test
    void ndcgShouldBeOneForIdealRanking() {
        // 理想排序：高相关在前。qrels: c1=3, c2=2, c3=1
        Map<String, Integer> qrels = Map.of("c1", 3, "c2", 2, "c3", 1);
        List<String> ideal = List.of("c1", "c2", "c3");
        assertThat(RetrievalMetrics.ndcgAtK(ideal, qrels, 3)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void ndcgShouldDropWhenRelevantRankedLower() {
        Map<String, Integer> qrels = Map.of("c1", 3, "c2", 2, "c3", 1);
        List<String> worse = List.of("c3", "c2", "c1"); // 反向
        double ndcg = RetrievalMetrics.ndcgAtK(worse, qrels, 3);
        assertThat(ndcg).isLessThan(1.0).isGreaterThan(0.0);
    }

    @Test
    void recallAtKShouldCountRelevantHits() {
        Map<String, Integer> qrels = Map.of("c1", 3, "c2", 2, "c4", 1); // 3 个相关
        List<String> ranked = List.of("c1", "x", "c2", "y", "z");
        // top4 命中 c1,c2 → 2/3
        assertThat(RetrievalMetrics.recallAtK(ranked, qrels, 4)).isCloseTo(2.0 / 3.0, within(1e-9));
        // top10 命中 c1,c2（c4 不在结果里）→ 仍 2/3
        assertThat(RetrievalMetrics.recallAtK(ranked, qrels, 10)).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    void mrrShouldUseFirstRelevantPosition() {
        Map<String, Integer> qrels = Map.of("c1", 3, "c2", 2);
        assertThat(RetrievalMetrics.mrr(List.of("x", "c2", "c1"), qrels)).isCloseTo(0.5, within(1e-9)); // 第2位
        assertThat(RetrievalMetrics.mrr(List.of("c1", "x"), qrels)).isCloseTo(1.0, within(1e-9)); // 第1位
        assertThat(RetrievalMetrics.mrr(List.of("a", "b"), qrels)).isEqualTo(0.0); // 无命中
    }

    @Test
    void shouldHandleEmptyQrelsGracefully() {
        assertThat(RetrievalMetrics.ndcgAtK(List.of("a"), Map.of(), 5)).isEqualTo(0.0);
        assertThat(RetrievalMetrics.recallAtK(List.of("a"), Map.of(), 5)).isEqualTo(0.0);
        assertThat(RetrievalMetrics.mrr(List.of("a"), Map.of())).isEqualTo(0.0);
    }
}

package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.RagChunk;
import cn.like.rag.model.SearchHit;
import cn.like.rag.sentinel.SentinelGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * 只验证 RerankService 中不依赖网络的解析/重排逻辑：parseScores 与 applyRanking。
 * LLM 调用与降级路径由集成验证覆盖。
 */
class RerankServiceTest {

    private RerankService newService() {
        return new RerankService(
                mock(OpenAiClientService.class),
                new ObjectMapper(),
                new RagProperties(),
                mock(SentinelGuard.class),
                mock(OperationLogService.class));
    }

    private SearchHit hit(String text) {
        RagChunk chunk = new RagChunk();
        chunk.setText(text);
        chunk.setDocumentName("d.md");
        return new SearchHit(chunk, 0.5);
    }

    @Test
    void parseScoresReadsIdAndScore() {
        Map<Integer, Double> scores = newService()
                .parseScores("[{\"id\":2,\"score\":3},{\"id\":1,\"score\":1}]", 2);
        assertThat(scores).containsEntry(2, 3.0).containsEntry(1, 1.0);
    }

    @Test
    void parseScoresToleratesCodeFenceAndExtraText() {
        String output = "这是结果：\n```json\n[{\"id\":1,\"score\":2}]\n```\n谢谢";
        assertThat(newService().parseScores(output, 1)).containsEntry(1, 2.0);
    }

    @Test
    void parseScoresClampsAndDropsOutOfRangeIds() {
        Map<Integer, Double> scores = newService()
                .parseScores("[{\"id\":1,\"score\":9},{\"id\":5,\"score\":2}]", 2);
        assertThat(scores).containsEntry(1, 3.0); // 9 clamped to 3
        assertThat(scores).doesNotContainKey(5);  // id 5 out of candidate range
    }

    @Test
    void parseScoresReturnsEmptyOnGarbage() {
        assertThat(newService().parseScores("no json here", 3)).isEmpty();
    }

    @Test
    void applyRankingReordersByScoreDescAndWritesNormalizedScore() {
        List<SearchHit> candidates = List.of(hit("a"), hit("b"), hit("c")); // ids 1,2,3
        Map<Integer, Double> scores = Map.of(1, 1.0, 2, 3.0, 3, 2.0);

        List<SearchHit> ranked = newService().applyRanking(new ArrayList<>(candidates), scores, 3);

        assertThat(ranked).extracting(h -> h.getChunk().getText())
                .containsExactly("b", "c", "a");
        assertThat(ranked.get(0).getScore()).isCloseTo(1.0, within(1e-9)); // 3/3
        assertThat(ranked.get(2).getScore()).isCloseTo(1.0 / 3.0, within(1e-9)); // 1/3
    }

    @Test
    void applyRankingIsStableForTies() {
        List<SearchHit> candidates = List.of(hit("x"), hit("y"));
        Map<Integer, Double> tie = Map.of(1, 2.0, 2, 2.0);

        List<SearchHit> ranked = newService().applyRanking(candidates, tie, 2);

        assertThat(ranked).extracting(h -> h.getChunk().getText())
                .containsExactly("x", "y"); // 原相对顺序保持
    }

    @Test
    void applyRankingFallsBackToOriginalOrderWhenNoScores() {
        List<SearchHit> candidates = List.of(hit("a"), hit("b"), hit("c"));
        List<SearchHit> ranked = newService().applyRanking(candidates, Map.of(), 2);
        assertThat(ranked).extracting(h -> h.getChunk().getText()).containsExactly("a", "b");
    }

    @Test
    void applyRankingTrimsToTopK() {
        List<SearchHit> candidates = List.of(hit("a"), hit("b"), hit("c"));
        Map<Integer, Double> scores = Map.of(1, 1.0, 2, 3.0, 3, 2.0);
        List<SearchHit> ranked = newService().applyRanking(candidates, scores, 2);
        assertThat(ranked).hasSize(2);
        assertThat(ranked).extracting(h -> h.getChunk().getText()).containsExactly("b", "c");
    }
}

package cn.like.rag.agent.tool;

import cn.like.rag.model.SearchHit;

import java.util.List;

/**
 * 工具执行结果。{@code observation} 是回灌给 LLM 的文本观察；
 * {@code hits} 是工具检索到的证据（仅检索类工具非空），由 Agent 累积用于最终答案的引用。
 */
public class ToolResult {

    private final String observation;
    private final List<SearchHit> hits;

    private ToolResult(String observation, List<SearchHit> hits) {
        this.observation = observation;
        this.hits = hits == null ? List.of() : hits;
    }

    public static ToolResult text(String observation) {
        return new ToolResult(observation, List.of());
    }

    public static ToolResult withHits(String observation, List<SearchHit> hits) {
        return new ToolResult(observation, hits);
    }

    public String getObservation() {
        return observation;
    }

    public List<SearchHit> getHits() {
        return hits;
    }
}

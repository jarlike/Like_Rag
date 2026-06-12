package cn.like.rag.agent;

import cn.like.rag.model.SearchHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 Agent 运行的共享状态：工具调用预算、超时截止、以及跨子任务累积的证据。
 * 安全边界（最大工具调用次数、最大执行时间）集中在此校验，对应项目书第六章第 4 节。
 */
public class AgentContext {

    private final int maxToolCalls;
    private final long deadlineEpochMillis;
    private int toolCallCount;
    private String stopReason;

    /** 证据去重：按 chunk id 保留分数最高的一条，避免多轮检索重复引用。 */
    private final Map<String, SearchHit> evidence = new LinkedHashMap<>();

    public AgentContext(int maxToolCalls, long maxRunMillis, long nowEpochMillis) {
        this.maxToolCalls = Math.max(1, maxToolCalls);
        this.deadlineEpochMillis = nowEpochMillis + Math.max(1000, maxRunMillis);
    }

    public boolean budgetExhausted(long nowEpochMillis) {
        if (toolCallCount >= maxToolCalls) {
            stopReason = "已达最大工具调用次数 " + maxToolCalls;
            return true;
        }
        if (nowEpochMillis >= deadlineEpochMillis) {
            stopReason = "已达最大执行时间";
            return true;
        }
        return false;
    }

    public void incrementToolCall() {
        toolCallCount++;
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public long remainingMillis(long nowEpochMillis) {
        return Math.max(0, deadlineEpochMillis - nowEpochMillis);
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public void addHits(List<SearchHit> hits) {
        if (hits == null) {
            return;
        }
        for (SearchHit hit : hits) {
            if (hit == null || hit.getChunk() == null) {
                continue;
            }
            String key = hit.getChunk().getId();
            if (key == null) {
                key = hit.getChunk().getDocumentId() + "#" + hit.getChunk().getChunkIndex();
            }
            SearchHit existing = evidence.get(key);
            if (existing == null || hit.getScore() > existing.getScore()) {
                evidence.put(key, hit);
            }
        }
    }

    /** 当前累积证据，按分数从高到低，最多 limit 条。 */
    public List<SearchHit> evidence(int limit) {
        List<SearchHit> all = new ArrayList<>(evidence.values());
        all.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());
        if (limit > 0 && all.size() > limit) {
            return new ArrayList<>(all.subList(0, limit));
        }
        return all;
    }

    public boolean hasEvidence() {
        return !evidence.isEmpty();
    }
}

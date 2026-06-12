package cn.like.rag.agent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次 Agent 运行的完整轨迹：计划 + 各步骤 + 最终答案 + 停止原因 + 资源消耗。
 * 由 {@code AgentTraceService} 维护，可通过 /api/agent/traces 复盘。
 */
public class AgentTrace {

    private String id;
    private String question;
    private List<String> plan = new ArrayList<>();
    private List<AgentStep> steps = new ArrayList<>();
    private String finalAnswer;
    private String stopReason;
    private String status = "RUNNING";
    private int toolCalls;
    private long elapsedMillis;
    private final LocalDateTime createdAt = LocalDateTime.now();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<String> getPlan() {
        return plan;
    }

    public void setPlan(List<String> plan) {
        this.plan = plan;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentStep> steps) {
        this.steps = steps;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(int toolCalls) {
        this.toolCalls = toolCalls;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public void setElapsedMillis(long elapsedMillis) {
        this.elapsedMillis = elapsedMillis;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

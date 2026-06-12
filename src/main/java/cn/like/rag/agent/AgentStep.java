package cn.like.rag.agent;

import java.time.LocalDateTime;

/**
 * Agent 执行轨迹中的单步记录，覆盖计划、思考、工具调用、观察、合成与校验各阶段，
 * 对应项目书第七章 {@code AgentTraceService} 的可观测需求。
 */
public class AgentStep {

    private int index;
    private String phase;
    private String subtask;
    private String thought;
    private String tool;
    private Object toolInput;
    private String observation;
    private LocalDateTime createdAt = LocalDateTime.now();

    public AgentStep() {
    }

    public AgentStep(int index, String phase) {
        this.index = index;
        this.phase = phase;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getSubtask() {
        return subtask;
    }

    public void setSubtask(String subtask) {
        this.subtask = subtask;
    }

    public String getThought() {
        return thought;
    }

    public void setThought(String thought) {
        this.thought = thought;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public Object getToolInput() {
        return toolInput;
    }

    public void setToolInput(Object toolInput) {
        this.toolInput = toolInput;
    }

    public String getObservation() {
        return observation;
    }

    public void setObservation(String observation) {
        this.observation = observation;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

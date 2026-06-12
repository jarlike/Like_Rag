package cn.like.rag.agent;

import cn.like.rag.service.OperationLogService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记录并保存 Agent 执行轨迹（计划 / 思考 / 工具调用 / 观察 / 合成 / 校验），对应项目书第七章 {@code AgentTraceService}。
 * 轨迹在内存中按数量上限滚动保存，关键步骤同时写操作日志，便于调试与复盘。
 */
@Service
public class AgentTraceService {

    private static final int MAX_TRACES = 200;

    private final OperationLogService operationLogService;
    private final Map<String, AgentTrace> traces = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AgentTrace> eldest) {
                    return size() > MAX_TRACES;
                }
            });

    public AgentTraceService(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    public AgentTrace start(String question) {
        AgentTrace trace = new AgentTrace();
        trace.setId(UUID.randomUUID().toString());
        trace.setQuestion(question);
        traces.put(trace.getId(), trace);
        return trace;
    }

    /** 追加一步并视阶段决定是否落操作日志（工具调用/停止/最终答案落盘，其余仅入轨迹）。 */
    public AgentStep record(AgentTrace trace, String phase, String subtask, String thought,
                            String tool, Object toolInput, String observation) {
        AgentStep step = new AgentStep(trace.getSteps().size(), phase);
        step.setSubtask(subtask);
        step.setThought(thought);
        step.setTool(tool);
        step.setToolInput(toolInput);
        step.setObservation(observation);
        trace.getSteps().add(step);

        if ("TOOL".equals(phase) || "STOP".equals(phase) || "FINAL".equals(phase)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("traceId", trace.getId());
            detail.put("phase", phase);
            if (tool != null) {
                detail.put("tool", tool);
            }
            if (observation != null) {
                detail.put("observation", observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);
            }
            operationLogService.info("AGENT_STEP", "Agent " + phase, null, detail);
        }
        return step;
    }

    public void finish(AgentTrace trace, String status, String finalAnswer, String stopReason,
                       int toolCalls, long elapsedMillis) {
        trace.setStatus(status);
        trace.setFinalAnswer(finalAnswer);
        trace.setStopReason(stopReason);
        trace.setToolCalls(toolCalls);
        trace.setElapsedMillis(elapsedMillis);
    }

    public AgentTrace get(String id) {
        return traces.get(id);
    }

    public List<AgentTrace> latest(int limit) {
        synchronized (traces) {
            List<AgentTrace> all = new ArrayList<>(traces.values());
            Collections.reverse(all);
            if (limit > 0 && all.size() > limit) {
                return new ArrayList<>(all.subList(0, limit));
            }
            return all;
        }
    }
}

package cn.like.rag.agent;

import cn.like.rag.agent.tool.AgentTool;
import cn.like.rag.agent.tool.ToolResult;
import cn.like.rag.config.RagProperties;
import cn.like.rag.service.OpenAiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 工具调用循环（项目书第六章第 3 节）：在子任务内"边想边做"——
 * LLM 决定调用哪个工具、用已得观察决定下一步，直到给出子任务结论或触达停止条件。
 *
 * <p>停止条件（硬边界，对应第六章第 4 节）：子任务最大步数、全局最大工具调用次数、全局最大执行时间。
 * 工具调用失败转为观察文本继续，不做无限重试。
 */
@Service
public class AgentToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);

    private final Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
    private final OpenAiClientService openAiClientService;
    private final ObjectMapper objectMapper;
    private final AgentTraceService traceService;
    private final RagProperties properties;

    public AgentToolExecutor(List<AgentTool> tools,
                             OpenAiClientService openAiClientService,
                             ObjectMapper objectMapper,
                             AgentTraceService traceService,
                             RagProperties properties) {
        for (AgentTool tool : tools) {
            this.toolsByName.put(tool.name(), tool);
        }
        this.openAiClientService = openAiClientService;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
        this.properties = properties;
    }

    /**
     * 解决单个子任务：通过 ReAct 循环按需调用工具积累证据（写入 {@code ctx}），返回该子任务的简短结论。
     */
    public String solve(String originalQuestion, String subtask, AgentContext ctx, AgentTrace trace) {
        int maxSteps = Math.max(1, properties.getAgent().getMaxReactStepsPerSubtask());
        StringBuilder transcript = new StringBuilder();

        for (int step = 0; step < maxSteps; step++) {
            if (ctx.budgetExhausted(System.currentTimeMillis())) {
                traceService.record(trace, "STOP", subtask, null, null, null, ctx.getStopReason());
                return "（已触达资源上限：" + ctx.getStopReason() + "，基于已有证据回答）";
            }

            String raw;
            try {
                raw = openAiClientService.complete(reactSystem(), reactUser(originalQuestion, subtask, transcript, ctx), 500);
            } catch (Exception e) {
                log.warn("ReAct 决策调用失败: {}", e.getMessage());
                traceService.record(trace, "STOP", subtask, null, null, null, "LLM 决策失败: " + e.getMessage());
                return "（决策调用失败，基于已有证据回答）";
            }

            ReactDecision decision = parseDecision(raw);
            if (decision.isFinal()) {
                traceService.record(trace, "REACT", subtask, decision.thought, null, null, decision.finalAnswer);
                return decision.finalAnswer;
            }

            AgentTool tool = toolsByName.get(decision.action);
            if (tool == null) {
                String obs = "未知工具: " + decision.action + "。可用工具: " + String.join(", ", toolsByName.keySet());
                transcript.append("Thought: ").append(decision.thought).append("\n")
                        .append("Action: ").append(decision.action).append("\n")
                        .append("Observation: ").append(obs).append("\n");
                traceService.record(trace, "TOOL", subtask, decision.thought, decision.action, decision.actionInput, obs);
                continue;
            }

            if (ctx.budgetExhausted(System.currentTimeMillis())) {
                traceService.record(trace, "STOP", subtask, null, null, null, ctx.getStopReason());
                return "（已触达资源上限：" + ctx.getStopReason() + "，基于已有证据回答）";
            }

            ctx.incrementToolCall();
            Map<String, Object> toolInput = decision.actionInput == null ? new LinkedHashMap<>() : decision.actionInput;
            // citation_check 需要知道当前证据条数，由执行器注入，保证校验与真实累积证据一致。
            if ("citation_check".equals(decision.action)) {
                toolInput.putIfAbsent("evidenceCount", ctx.evidence(0).size());
            }

            String observation;
            try {
                ToolResult result = tool.execute(toolInput);
                observation = result.getObservation();
                ctx.addHits(result.getHits());
            } catch (Exception e) {
                observation = "ERROR: 工具 " + decision.action + " 执行异常: " + e.getMessage();
                log.warn("工具 {} 执行异常", decision.action, e);
            }

            transcript.append("Thought: ").append(decision.thought).append("\n")
                    .append("Action: ").append(decision.action)
                    .append(" ").append(toolInput).append("\n")
                    .append("Observation: ").append(observation).append("\n");
            traceService.record(trace, "TOOL", subtask, decision.thought, decision.action, toolInput, observation);
        }

        return "（子任务已达最大步数，基于已有证据回答）";
    }

    private String reactSystem() {
        StringBuilder tools = new StringBuilder();
        for (AgentTool tool : toolsByName.values()) {
            tools.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        return "你是 RAG 系统的 ReAct 智能体。针对当前子任务，决定下一步：调用一个工具，或给出子任务结论。\n"
                + "可用工具：\n" + tools
                + "\n输出要求：只输出一个 JSON 对象，二选一：\n"
                + "1. 调用工具：{\"thought\":\"简短推理\",\"action\":\"工具名\",\"action_input\":{...}}\n"
                + "2. 结束子任务：{\"thought\":\"简短推理\",\"final\":\"该子任务的简短结论\"}\n"
                + "不要输出 JSON 以外的任何文字。证据已足够时尽快 final，避免无谓的工具调用。";
    }

    private String reactUser(String originalQuestion, String subtask, StringBuilder transcript, AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("原始问题：").append(originalQuestion).append("\n");
        sb.append("当前子任务：").append(subtask).append("\n");
        sb.append("剩余工具调用预算：").append(ctx.getMaxToolCalls() - ctx.getToolCallCount()).append(" 次\n");
        sb.append("已累积证据条数：").append(ctx.evidence(0).size()).append("\n");
        if (transcript.length() > 0) {
            sb.append("已执行步骤：\n").append(transcript);
        }
        sb.append("请输出下一步 JSON：");
        return sb.toString();
    }

    private ReactDecision parseDecision(String raw) {
        ReactDecision decision = new ReactDecision();
        if (raw == null || raw.isBlank()) {
            decision.finalAnswer = "（无决策输出）";
            return decision;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            // 非 JSON，按子任务结论处理，避免循环卡死。
            decision.finalAnswer = raw.trim();
            return decision;
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            JsonNode finalNode = node.has("final") ? node.get("final") : node.get("final_answer");
            if (finalNode != null && finalNode.isTextual() && !finalNode.asText().isBlank()) {
                decision.thought = text(node, "thought");
                decision.finalAnswer = finalNode.asText().trim();
                return decision;
            }
            decision.thought = text(node, "thought");
            decision.action = text(node, "action");
            JsonNode input = node.get("action_input");
            if (input != null && input.isObject()) {
                decision.actionInput = objectMapper.convertValue(input, Map.class);
            } else if (input != null && input.isTextual()) {
                decision.actionInput = new LinkedHashMap<>();
                decision.actionInput.put("query", input.asText());
            }
            if (decision.action == null || decision.action.isBlank()) {
                decision.finalAnswer = decision.thought != null ? decision.thought : raw.trim();
            }
        } catch (Exception e) {
            log.warn("ReAct 决策 JSON 解析失败，按结论处理: {}", e.getMessage());
            decision.finalAnswer = raw.trim();
        }
        return decision;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : null;
    }

    private static class ReactDecision {
        private String thought;
        private String action;
        private Map<String, Object> actionInput;
        private String finalAnswer;

        boolean isFinal() {
            return finalAnswer != null;
        }
    }
}

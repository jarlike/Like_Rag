package cn.like.rag.agent.tool;

import java.util.Map;

/**
 * ReAct 工具统一契约。每个工具是一个 Spring Bean，Agent 通过注入 {@code List<AgentTool>} 自动发现全部工具。
 * 对应项目书第六章第 3 节《第一批工具》。
 */
public interface AgentTool {

    /** 工具名（LLM 在 action 字段中引用，需稳定且唯一）。 */
    String name();

    /** 工具用途与入参说明，拼进 ReAct 提示，帮助 LLM 决定何时调用。 */
    String description();

    /**
     * 执行工具。
     *
     * @param args LLM 给出的 action_input（已解析为 Map），实现需对缺参/坏参做健壮处理。
     * @return 观察结果（回灌给 LLM）+ 可选证据 hits。
     */
    ToolResult execute(Map<String, Object> args);
}

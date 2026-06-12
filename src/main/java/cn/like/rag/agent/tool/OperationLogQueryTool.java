package cn.like.rag.agent.tool;

import cn.like.rag.model.RagOperationLog;
import cn.like.rag.service.OperationLogService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ReAct 工具：查询上传/索引/问答/限流等操作日志。用于追问"上次为什么失败/降级"或排查索引状态。
 */
@Component
public class OperationLogQueryTool implements AgentTool {

    private final OperationLogService operationLogService;

    public OperationLogQueryTool(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Override
    public String name() {
        return "operation_log_query";
    }

    @Override
    public String description() {
        return "查询最近的操作日志（上传/索引/问答/限流降级等）。"
                + "入参 {\"limit\": 20, \"type\": \"INDEX_FAILED\"}，type 可选用于过滤。";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        int limit = ToolArgs.asInt(args, "limit", 20);
        limit = Math.max(1, Math.min(limit, 100));
        String type = ToolArgs.asString(args, "type");

        List<RagOperationLog> logs = operationLogService.latest(limit);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (RagOperationLog log : logs) {
            if (type != null && !type.isBlank() && !type.equalsIgnoreCase(log.getType())) {
                continue;
            }
            sb.append("- ").append(log.getCreatedAt())
                    .append(" | ").append(log.getType())
                    .append(" | ").append(log.getMessage());
            if (log.getDocumentId() != null) {
                sb.append(" | doc=").append(log.getDocumentId());
            }
            sb.append("\n");
            shown++;
        }
        if (shown == 0) {
            return ToolResult.text("没有匹配的操作日志" + (type != null ? "（type=" + type + "）" : "") + "。");
        }
        return ToolResult.text("最近 " + shown + " 条操作日志：\n" + sb.toString().trim());
    }
}

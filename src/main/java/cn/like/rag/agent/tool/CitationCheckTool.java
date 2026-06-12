package cn.like.rag.agent.tool;

import cn.like.rag.agent.CitationVerifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ReAct 工具：校验答案是否带 [n] 引用且引用编号在证据范围内。
 * {@code evidenceCount} 由执行器注入为当前累积证据条数，Agent 也可显式传入。
 */
@Component
public class CitationCheckTool implements AgentTool {

    private final CitationVerifier citationVerifier;

    public CitationCheckTool(CitationVerifier citationVerifier) {
        this.citationVerifier = citationVerifier;
    }

    @Override
    public String name() {
        return "citation_check";
    }

    @Override
    public String description() {
        return "校验一段答案是否带有效 [n] 引用。入参 {\"answer\": \"待校验答案文本\"}。"
                + "返回是否通过、已引用编号、越界引用。生成最终答案前可用它自检。";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String answer = ToolArgs.asString(args, "answer");
        if (answer == null || answer.isBlank()) {
            return ToolResult.text("ERROR: citation_check 需要 answer 参数");
        }
        int evidenceCount = ToolArgs.asInt(args, "evidenceCount", 0);
        CitationVerifier.Result result = citationVerifier.verify(answer, evidenceCount);
        return ToolResult.text("引用校验：" + (result.isPassed() ? "通过" : "未通过")
                + " | 已引用=" + result.getCitedIndices()
                + " | 越界=" + result.getInvalidIndices()
                + " | 证据数=" + evidenceCount
                + " | " + result.getMessage());
    }
}

package cn.like.rag.agent;

import cn.like.rag.config.RagProperties;
import cn.like.rag.service.OpenAiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan-and-Solve 规划器（项目书第六章第 2 节）：先把复杂问题拆成有限、可执行的子任务，
 * 再交给 ReAct 逐个解决。规划失败或被关闭时安全退化为"单步=原问题"。
 */
@Service
public class AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanner.class);

    private final OpenAiClientService openAiClientService;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;

    public AgentPlanner(OpenAiClientService openAiClientService,
                        RagProperties properties,
                        ObjectMapper objectMapper) {
        this.openAiClientService = openAiClientService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<String> plan(String question) {
        int maxSubTasks = Math.max(1, properties.getAgent().getMaxSubTasks());
        if (!properties.getAgent().isPlanningEnabled()) {
            return List.of(question);
        }

        String system = "你是 RAG 系统的任务规划器。把用户问题拆解为完成回答所需的最少子任务（检索/对比/校验角度）。\n"
                + "要求：\n"
                + "1. 仅输出一个 JSON 数组，元素是字符串子任务，不要输出任何额外文字。\n"
                + "2. 子任务数量不超过 " + maxSubTasks + " 个；简单问题可只给 1 个。\n"
                + "3. 子任务要具体、可独立检索，按执行顺序排列。";
        String user = "用户问题：" + question + "\n请输出子任务 JSON 数组：";

        try {
            String raw = openAiClientService.complete(system, user, 400);
            List<String> tasks = parseJsonArray(raw);
            if (tasks.isEmpty()) {
                return List.of(question);
            }
            if (tasks.size() > maxSubTasks) {
                tasks = new ArrayList<>(tasks.subList(0, maxSubTasks));
            }
            return tasks;
        } catch (Exception e) {
            log.warn("Plan-and-Solve 规划失败，退化为单步: {}", e.getMessage());
            return List.of(question);
        }
    }

    private List<String> parseJsonArray(String raw) {
        List<String> tasks = new ArrayList<>();
        if (raw == null) {
            return tasks;
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return tasks;
        }
        String json = raw.substring(start, end + 1);
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    String task = item.isTextual() ? item.asText() : item.toString();
                    if (task != null && !task.isBlank()) {
                        tasks.add(task.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("子任务 JSON 解析失败: {}", e.getMessage());
        }
        return tasks;
    }
}

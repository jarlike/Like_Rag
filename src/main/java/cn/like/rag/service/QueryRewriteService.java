package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 追问改写：在多轮对话中，把依赖上文的追问（含指代、省略主语）改写成可独立检索的完整问题，
 * 提升 follow-up 的召回质量。对应《重排序与多轮对话项目书》第四章。
 *
 * <p><b>优雅降级</b>：开关关闭、无历史、未配置 Key、改写结果异常或 LLM 调用失败时，一律返回原问题，
 * 绝不让改写故障影响问答主链路。
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String SYSTEM_PROMPT =
            "你是对话问题改写器。根据对话历史，把用户最新的问题改写成一个不依赖历史也能独立理解的完整问题：\n"
                    + "消解“它/这个/那个/上面”等指代，补全省略的主语或对象。\n"
                    + "只输出改写后的问题本身，不要解释、不要加引号、不要加前缀。\n"
                    + "如果最新问题本身已经完整、或与历史无关，原样输出。";

    private static final int MAX_OUTPUT_TOKENS = 200;

    private final OpenAiClientService openAiClientService;
    private final RagProperties properties;
    private final OperationLogService operationLogService;

    public QueryRewriteService(OpenAiClientService openAiClientService,
                               RagProperties properties,
                               OperationLogService operationLogService) {
        this.openAiClientService = openAiClientService;
        this.properties = properties;
        this.operationLogService = operationLogService;
    }

    /**
     * 基于历史把 question 改写为独立问题；不可用/失败时返回原 question。
     */
    public String rewrite(String question, String historyText) {
        if (!properties.getConversation().isRewriteEnabled()
                || question == null || question.isBlank()
                || historyText == null || historyText.isBlank()
                || !openAiClientService.isConfigured()) {
            return question;
        }
        try {
            String user = "对话历史：\n" + historyText.trim()
                    + "\n\n最新问题：" + question.trim()
                    + "\n\n改写后的问题：";
            String raw = openAiClientService.complete(SYSTEM_PROMPT, user, MAX_OUTPUT_TOKENS);
            String rewritten = sanitize(raw, question);
            if (!rewritten.equals(question.trim())) {
                operationLogService.info("QUERY_REWRITE", "追问改写", null,
                        Map.of("original", question.trim(), "rewritten", rewritten));
            }
            return rewritten;
        } catch (RuntimeException e) {
            log.warn("Query rewrite failed, fallback to original: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 清洗模型输出：取首个非空行、去围栏/引号/前缀；异常（空、过长）则退回原问题。
     */
    private String sanitize(String raw, String question) {
        if (raw == null || raw.isBlank()) {
            return question.trim();
        }
        String line = raw.trim();
        int newline = line.indexOf('\n');
        if (newline >= 0) {
            line = line.substring(0, newline).trim();
        }
        line = line.replaceAll("^[`\"'“”]+", "").replaceAll("[`\"'“”]+$", "").trim();
        if (line.startsWith("改写后的问题：")) {
            line = line.substring("改写后的问题：".length()).trim();
        }
        // 防御：改写结果异常（空或异常膨胀）则退回原问题，避免污染检索。
        int maxLen = question.trim().length() * 5 + 200;
        if (line.isBlank() || line.length() > maxLen) {
            return question.trim();
        }
        return line;
    }
}

package cn.like.rag.agent;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.ChatResponse;
import cn.like.rag.model.SearchHit;
import cn.like.rag.sentinel.SentinelGuard;
import cn.like.rag.sentinel.SentinelResources;
import cn.like.rag.service.HybridSearchService;
import cn.like.rag.service.OpenAiClientService;
import cn.like.rag.service.OperationLogService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 编排器：把"计划 + 检索 + 校验 + 生成"串成多步闭环（项目书第六章）。
 *
 * <ol>
 *   <li>Plan-and-Solve 拆解子任务；</li>
 *   <li>对原问题做一次种子检索，保证始终有基础证据；</li>
 *   <li>对每个子任务跑 ReAct 工具循环，按需补充证据（受全局工具预算/时限约束）；</li>
 *   <li>基于累积证据用 GPT-5.5 生成带 [n] 引用的最终答案；</li>
 *   <li>CitationVerifier 校验引用，不合格时做一次修复重写。</li>
 * </ol>
 * 整个运行受 Sentinel {@code agent.toolLoop} 并发保护，硬边界由 {@link AgentContext} 统一约束。
 */
@Service
public class AgentService {

    private final AgentPlanner planner;
    private final AgentToolExecutor toolExecutor;
    private final OpenAiClientService openAiClientService;
    private final HybridSearchService hybridSearchService;
    private final CitationVerifier citationVerifier;
    private final AgentTraceService traceService;
    private final OperationLogService operationLogService;
    private final RagProperties properties;
    private final SentinelGuard sentinelGuard;

    public AgentService(AgentPlanner planner,
                        AgentToolExecutor toolExecutor,
                        OpenAiClientService openAiClientService,
                        HybridSearchService hybridSearchService,
                        CitationVerifier citationVerifier,
                        AgentTraceService traceService,
                        OperationLogService operationLogService,
                        RagProperties properties,
                        SentinelGuard sentinelGuard) {
        this.planner = planner;
        this.toolExecutor = toolExecutor;
        this.openAiClientService = openAiClientService;
        this.hybridSearchService = hybridSearchService;
        this.citationVerifier = citationVerifier;
        this.traceService = traceService;
        this.operationLogService = operationLogService;
        this.properties = properties;
        this.sentinelGuard = sentinelGuard;
    }

    public AgentResponse answer(String question, Integer topK) {
        if (!properties.getAgent().isEnabled()) {
            throw new IllegalStateException("Agent 能力未开启 (rag.agent.enabled=false)");
        }
        if (!openAiClientService.isConfigured()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured, Agent 需要 GPT-5.5 进行规划与决策");
        }
        return sentinelGuard.call(SentinelResources.AGENT_TOOL_LOOP, () -> run(question, topK));
    }

    private AgentResponse run(String question, Integer topK) {
        long start = System.currentTimeMillis();
        AgentTrace trace = traceService.start(question);
        AgentContext ctx = new AgentContext(
                properties.getAgent().getMaxToolCalls(),
                properties.getAgent().getMaxRunMillis(),
                start);
        int seedTopK = topK != null ? Math.max(1, topK) : properties.getAgent().getToolSearchTopK();

        // 1. Plan-and-Solve 拆解
        List<String> plan = planner.plan(question);
        trace.setPlan(plan);
        traceService.record(trace, "PLAN", null, "拆解为 " + plan.size() + " 个子任务", null, null, String.join(" | ", plan));

        // 2. 种子检索：保证最终合成始终有基础证据
        if (!ctx.budgetExhausted(System.currentTimeMillis())) {
            ctx.incrementToolCall();
            List<SearchHit> seed = hybridSearchService.search(question, seedTopK);
            ctx.addHits(seed);
            traceService.record(trace, "TOOL", null, "对原问题做种子检索", "hybrid_search",
                    Map.of("query", question, "topK", seedTopK), "种子检索命中 " + seed.size() + " 条");
        }

        // 3. 逐子任务跑 ReAct
        for (String subtask : plan) {
            if (ctx.budgetExhausted(System.currentTimeMillis())) {
                traceService.record(trace, "STOP", subtask, null, null, null, ctx.getStopReason());
                break;
            }
            toolExecutor.solve(question, subtask, ctx, trace);
        }

        // 4. 基于累积证据合成最终答案
        List<SearchHit> evidence = ctx.evidence(properties.getFinalTopK());
        String provider = "openai";
        String answer;
        if (evidence.isEmpty()) {
            provider = "agent-no-evidence";
            answer = "经过多轮检索仍未找到足够证据，无法可靠回答。建议补充相关文档或更换问法。";
        } else {
            answer = openAiClientService.generateAnswer(question, evidence);
        }
        traceService.record(trace, "SYNTHESIZE", null, "基于 " + evidence.size() + " 条证据生成答案", null, null, null);

        // 5. 引用校验 + 一次修复
        CitationVerifier.Result check = citationVerifier.verify(answer, evidence);
        if (!check.isPassed() && !evidence.isEmpty()) {
            String repaired = repairCitations(question, evidence, answer);
            CitationVerifier.Result recheck = citationVerifier.verify(repaired, evidence);
            traceService.record(trace, "VERIFY", null, "首次校验未通过，尝试修复引用", null, null,
                    check.getMessage() + " -> " + recheck.getMessage());
            if (recheck.isPassed() || recheck.getCitedIndices().size() >= check.getCitedIndices().size()) {
                answer = repaired;
                check = recheck;
            }
        } else {
            traceService.record(trace, "VERIFY", null, "引用校验", null, null, check.getMessage());
        }

        long elapsed = System.currentTimeMillis() - start;
        String stopReason = ctx.getStopReason() != null ? ctx.getStopReason() : "正常完成";
        traceService.finish(trace, "COMPLETED", answer, stopReason, ctx.getToolCallCount(), elapsed);
        traceService.record(trace, "FINAL", null, null, null, null, answer);

        operationLogService.info("AGENT_DONE", "Agent 回答完成", null, Map.of(
                "traceId", trace.getId(),
                "subTasks", plan.size(),
                "toolCalls", ctx.getToolCallCount(),
                "evidence", evidence.size(),
                "citationPassed", check.isPassed(),
                "elapsedMillis", elapsed,
                "stopReason", stopReason));

        return buildResponse(trace, question, answer, provider, evidence, check, stopReason, ctx.getToolCallCount(), elapsed);
    }

    private String repairCitations(String question, List<SearchHit> evidence, String previousAnswer) {
        try {
            String system = "你在修复一段 RAG 答案的引用。只能使用提供的 Context 作答，"
                    + "每个关键结论后用 [n] 引用对应编号（n 必须在 1.." + evidence.size() + " 内）。"
                    + "若证据确实不足，请直接说明“检索到的文档片段不足以回答”。只输出修复后的答案正文。";
            StringBuilder user = new StringBuilder();
            user.append("Question:\n").append(question).append("\n\n");
            user.append("原答案（缺少有效引用）：\n").append(previousAnswer).append("\n\n");
            user.append("Context (按相似度从高到低，编号即引用号):\n");
            for (int i = 0; i < evidence.size(); i++) {
                SearchHit hit = evidence.get(i);
                user.append("[").append(i + 1).append("] ")
                        .append("score=").append(String.format(Locale.ROOT, "%.4f", hit.getScore()))
                        .append(" | document=").append(hit.getChunk().getDocumentName())
                        .append("\n").append(hit.getChunk().getText()).append("\n\n");
            }
            return openAiClientService.complete(system, user.toString(), 700);
        } catch (Exception e) {
            return previousAnswer;
        }
    }

    private AgentResponse buildResponse(AgentTrace trace, String question, String answer, String provider,
                                        List<SearchHit> evidence, CitationVerifier.Result check,
                                        String stopReason, int toolCalls, long elapsed) {
        AgentResponse response = new AgentResponse();
        response.setTraceId(trace.getId());
        response.setQuestion(question);
        response.setAnswer(answer);
        response.setProvider(provider);
        response.setModel(openAiClientService.chatModel());
        response.setPlan(trace.getPlan());
        response.setSteps(trace.getSteps());
        response.setHits(evidence);
        response.setCitations(toCitations(evidence));
        response.setCitationCheck(check);
        response.setStopReason(stopReason);
        response.setToolCalls(toolCalls);
        response.setElapsedMillis(elapsed);
        return response;
    }

    private List<ChatResponse.Citation> toCitations(List<SearchHit> hits) {
        return hits.stream().map(hit -> {
            ChatResponse.Citation citation = new ChatResponse.Citation();
            citation.setDocumentId(hit.getChunk().getDocumentId());
            citation.setDocumentName(hit.getChunk().getDocumentName());
            citation.setChunkIndex(hit.getChunk().getChunkIndex());
            citation.setSectionPath(hit.getChunk().getSectionPath());
            citation.setScore(hit.getScore());
            citation.setSnippet(snippet(hit.getChunk().getText(), 180));
            return citation;
        }).toList();
    }

    private String snippet(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}

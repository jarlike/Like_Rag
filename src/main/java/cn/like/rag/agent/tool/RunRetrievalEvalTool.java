package cn.like.rag.agent.tool;

import cn.like.rag.eval.EvalQuery;
import cn.like.rag.eval.EvalReport;
import cn.like.rag.eval.EvalRequest;
import cn.like.rag.eval.RetrievalEvalService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 工具：运行检索评测，输出 dense/sparse/hybrid 的 nDCG@K / Recall@K / MRR。
 * 评测指标异常时由 Agent 调用以定位是召回还是排序问题（对应项目书第六章 ReAct 场景）。
 */
@Component
public class RunRetrievalEvalTool implements AgentTool {

    private final RetrievalEvalService retrievalEvalService;

    public RunRetrievalEvalTool(RetrievalEvalService retrievalEvalService) {
        this.retrievalEvalService = retrievalEvalService;
    }

    @Override
    public String name() {
        return "run_retrieval_eval";
    }

    @Override
    public String description() {
        return "运行检索评测对比三路质量。两种入参：(1) 快速诊断单条："
                + "{\"query\": \"问题\", \"expectedDocumentIds\": [\"docId1\"]}；"
                + "(2) 批量：{\"queries\": [{\"text\": \"问题\", \"qrels\": {\"docId\": 3}}], \"relevanceField\": \"document\"}。";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> args) {
        EvalRequest request = new EvalRequest();
        List<EvalQuery> queries = new ArrayList<>();
        try {
            if (args != null && args.get("queries") instanceof List<?> rawQueries) {
                for (Object raw : rawQueries) {
                    if (!(raw instanceof Map<?, ?> map)) {
                        continue;
                    }
                    EvalQuery q = new EvalQuery();
                    q.setText(String.valueOf(map.get("text")));
                    Map<String, Integer> qrels = new LinkedHashMap<>();
                    Object rawQrels = map.get("qrels");
                    if (rawQrels instanceof Map<?, ?> qm) {
                        for (Map.Entry<?, ?> e : qm.entrySet()) {
                            qrels.put(String.valueOf(e.getKey()), toInt(e.getValue()));
                        }
                    }
                    q.setQrels(qrels);
                    queries.add(q);
                }
                if (args.get("relevanceField") != null) {
                    request.setRelevanceField(String.valueOf(args.get("relevanceField")));
                }
            } else {
                String query = ToolArgs.asString(args, "query");
                if (query == null || query.isBlank()) {
                    return ToolResult.text("ERROR: run_retrieval_eval 需要 query+expectedDocumentIds 或 queries 数组");
                }
                EvalQuery q = new EvalQuery();
                q.setText(query);
                Map<String, Integer> qrels = new LinkedHashMap<>();
                for (String docId : ToolArgs.asStringList(args, "expectedDocumentIds")) {
                    qrels.put(docId, 3);
                }
                q.setQrels(qrels);
                request.setRelevanceField("document");
                queries.add(q);
            }

            if (queries.isEmpty()) {
                return ToolResult.text("ERROR: 未解析到有效的评测 query");
            }
            request.setQueries(queries);
            EvalReport report = retrievalEvalService.run(request);
            return ToolResult.text(format(report));
        } catch (Exception e) {
            return ToolResult.text("ERROR: 评测执行失败：" + e.getMessage());
        }
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String format(EvalReport report) {
        StringBuilder sb = new StringBuilder("检索评测结果（queries=" + report.getQueryCount()
                + ", relevanceField=" + report.getRelevanceField() + "）：\n");
        report.getRoutes().forEach((route, m) -> sb.append(String.format("- %-7s nDCG=%s Recall=%s MRR=%.4f%n",
                route, m.getNdcg(), m.getRecall(), m.getMrr())));
        if (report.getGate() != null) {
            sb.append("门禁: ").append(report.getGate().isPassed() ? "PASS" : "FAIL")
                    .append(" - ").append(report.getGate().getMessage());
        }
        return sb.toString().trim();
    }
}

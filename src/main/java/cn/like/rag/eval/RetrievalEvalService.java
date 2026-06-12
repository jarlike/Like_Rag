package cn.like.rag.eval;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.SearchHit;
import cn.like.rag.service.HybridSearchService;
import cn.like.rag.service.OperationLogService;
import cn.like.rag.service.VectorSearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 统一运行 dense / sparse / hybrid 三路检索评测，输出 nDCG@K / Recall@K / MRR，
 * 并对 hybrid 做回归门禁判定。对应项目书第七章 {@code RetrievalEvalService} 与第八章第一阶段。
 *
 * <p>三路来源：dense=向量检索、sparse=全文检索、hybrid=RRF+MMR 融合检索，
 * 复用线上同一套检索服务，保证评测与线上行为一致。
 */
@Service
public class RetrievalEvalService {

    public static final List<String> ROUTES = List.of("dense", "sparse", "hybrid");

    private final VectorSearchService vectorSearchService;
    private final HybridSearchService hybridSearchService;
    private final RagProperties properties;
    private final OperationLogService operationLogService;

    public RetrievalEvalService(VectorSearchService vectorSearchService,
                                HybridSearchService hybridSearchService,
                                RagProperties properties,
                                OperationLogService operationLogService) {
        this.vectorSearchService = vectorSearchService;
        this.hybridSearchService = hybridSearchService;
        this.properties = properties;
        this.operationLogService = operationLogService;
    }

    public EvalReport run(EvalRequest request) {
        if (request == null || request.getQueries() == null || request.getQueries().isEmpty()) {
            throw new IllegalArgumentException("评测请求必须包含至少一条 query");
        }
        int[] ks = (request.getKs() != null && request.getKs().length > 0)
                ? request.getKs() : properties.getEval().getKs();
        int topN = request.getTopN() != null ? Math.max(1, request.getTopN()) : properties.getEval().getTopN();
        String field = (request.getRelevanceField() == null || request.getRelevanceField().isBlank())
                ? "document" : request.getRelevanceField().trim().toLowerCase();
        int maxK = Arrays.stream(ks).max().orElse(10);
        int fetch = Math.max(topN, maxK);

        Function<SearchHit, String> idFn = "chunk".equals(field)
                ? hit -> hit.getChunk().getId()
                : hit -> hit.getChunk().getDocumentId();

        List<EvalQuery> queries = request.getQueries();
        Map<String, EvalReport.RouteMetrics> routeMetrics = new LinkedHashMap<>();
        Map<String, double[]> ndcgSum = new LinkedHashMap<>();
        Map<String, double[]> recallSum = new LinkedHashMap<>();
        Map<String, Double> mrrSum = new LinkedHashMap<>();
        for (String route : ROUTES) {
            ndcgSum.put(route, new double[ks.length]);
            recallSum.put(route, new double[ks.length]);
            mrrSum.put(route, 0.0);
        }

        for (EvalQuery query : queries) {
            Map<String, Integer> qrels = query.getQrels() == null ? Map.of() : query.getQrels();
            Map<String, List<String>> routeRanked = new LinkedHashMap<>();
            routeRanked.put("dense", rankedIds(vectorSearchService.search(query.getText(), fetch), idFn));
            routeRanked.put("sparse", rankedIds(vectorSearchService.searchSparse(query.getText(), fetch), idFn));
            routeRanked.put("hybrid", rankedIds(hybridSearchService.search(query.getText(), fetch), idFn));

            for (String route : ROUTES) {
                List<String> ranked = routeRanked.get(route);
                double[] nd = ndcgSum.get(route);
                double[] rc = recallSum.get(route);
                for (int i = 0; i < ks.length; i++) {
                    nd[i] += RetrievalMetrics.ndcgAtK(ranked, qrels, ks[i]);
                    rc[i] += RetrievalMetrics.recallAtK(ranked, qrels, ks[i]);
                }
                mrrSum.put(route, mrrSum.get(route) + RetrievalMetrics.mrr(ranked, qrels));
            }
        }

        int n = queries.size();
        for (String route : ROUTES) {
            EvalReport.RouteMetrics rm = new EvalReport.RouteMetrics();
            double[] nd = ndcgSum.get(route);
            double[] rc = recallSum.get(route);
            for (int i = 0; i < ks.length; i++) {
                rm.getNdcg().put(String.valueOf(ks[i]), round(nd[i] / n));
                rm.getRecall().put(String.valueOf(ks[i]), round(rc[i] / n));
            }
            rm.setMrr(round(mrrSum.get(route) / n));
            routeMetrics.put(route, rm);
        }

        EvalReport report = new EvalReport();
        report.setQueryCount(n);
        report.setKs(ks);
        report.setRelevanceField(field);
        report.setRoutes(routeMetrics);
        report.setGate(buildGate(routeMetrics.get("hybrid"), ks));

        operationLogService.info("EVAL_RUN", "检索评测完成", null, Map.of(
                "queryCount", n,
                "relevanceField", field,
                "gatePassed", report.getGate() != null && report.getGate().isPassed(),
                "hybridMrr", routeMetrics.get("hybrid").getMrr()));
        return report;
    }

    private List<String> rankedIds(List<SearchHit> hits, Function<SearchHit, String> idFn) {
        // document 维度可能出现同文档多 chunk，按排名去重保留首次出现，避免 Recall 计算重复命中。
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (SearchHit hit : hits) {
            String id = idFn.apply(hit);
            if (id != null) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    private EvalReport.Gate buildGate(EvalReport.RouteMetrics hybrid, int[] ks) {
        int gateK = Arrays.stream(ks).anyMatch(k -> k == 10) ? 10 : Arrays.stream(ks).max().orElse(10);
        RagProperties.Eval cfg = properties.getEval();
        double ndcg = hybrid.getNdcg().getOrDefault(String.valueOf(gateK), 0.0);
        double recall = hybrid.getRecall().getOrDefault(String.valueOf(gateK), 0.0);
        double mrr = hybrid.getMrr();

        EvalReport.Gate gate = new EvalReport.Gate();
        gate.setGateK(gateK);
        gate.setNdcg(ndcg);
        gate.setNdcgGate(cfg.getNdcgGate());
        gate.setRecall(recall);
        gate.setRecallGate(cfg.getRecallGate());
        gate.setMrr(mrr);
        gate.setMrrGate(cfg.getMrrGate());
        boolean passed = ndcg >= cfg.getNdcgGate() && recall >= cfg.getRecallGate() && mrr >= cfg.getMrrGate();
        gate.setPassed(passed);
        gate.setMessage(passed
                ? String.format("hybrid 门禁通过：nDCG@%d=%.4f, Recall@%d=%.4f, MRR=%.4f", gateK, ndcg, gateK, recall, mrr)
                : String.format("hybrid 门禁未通过：nDCG@%d=%.4f(>=%.2f), Recall@%d=%.4f(>=%.2f), MRR=%.4f(>=%.2f)",
                        gateK, ndcg, cfg.getNdcgGate(), gateK, recall, cfg.getRecallGate(), mrr, cfg.getMrrGate()));
        return gate;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}

package cn.like.rag.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测结果：三路（dense/sparse/hybrid）各自的指标 + hybrid 回归门禁判定。
 */
public class EvalReport {

    private int queryCount;
    private int[] ks;
    private String relevanceField;
    private Map<String, RouteMetrics> routes = new LinkedHashMap<>();
    private Gate gate;

    public int getQueryCount() {
        return queryCount;
    }

    public void setQueryCount(int queryCount) {
        this.queryCount = queryCount;
    }

    public int[] getKs() {
        return ks;
    }

    public void setKs(int[] ks) {
        this.ks = ks;
    }

    public String getRelevanceField() {
        return relevanceField;
    }

    public void setRelevanceField(String relevanceField) {
        this.relevanceField = relevanceField;
    }

    public Map<String, RouteMetrics> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, RouteMetrics> routes) {
        this.routes = routes;
    }

    public Gate getGate() {
        return gate;
    }

    public void setGate(Gate gate) {
        this.gate = gate;
    }

    /** 单路指标：nDCG@K / Recall@K（按 K 索引）与 MRR，均为查询平均值。 */
    public static class RouteMetrics {
        private Map<String, Double> ndcg = new LinkedHashMap<>();
        private Map<String, Double> recall = new LinkedHashMap<>();
        private double mrr;

        public Map<String, Double> getNdcg() {
            return ndcg;
        }

        public void setNdcg(Map<String, Double> ndcg) {
            this.ndcg = ndcg;
        }

        public Map<String, Double> getRecall() {
            return recall;
        }

        public void setRecall(Map<String, Double> recall) {
            this.recall = recall;
        }

        public double getMrr() {
            return mrr;
        }

        public void setMrr(double mrr) {
            this.mrr = mrr;
        }
    }

    /** hybrid 回归门禁判定（对应项目书第四章第 5 节）。 */
    public static class Gate {
        private int gateK;
        private boolean passed;
        private double ndcg;
        private double ndcgGate;
        private double recall;
        private double recallGate;
        private double mrr;
        private double mrrGate;
        private String message;

        public int getGateK() {
            return gateK;
        }

        public void setGateK(int gateK) {
            this.gateK = gateK;
        }

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }

        public double getNdcg() {
            return ndcg;
        }

        public void setNdcg(double ndcg) {
            this.ndcg = ndcg;
        }

        public double getNdcgGate() {
            return ndcgGate;
        }

        public void setNdcgGate(double ndcgGate) {
            this.ndcgGate = ndcgGate;
        }

        public double getRecall() {
            return recall;
        }

        public void setRecall(double recall) {
            this.recall = recall;
        }

        public double getRecallGate() {
            return recallGate;
        }

        public void setRecallGate(double recallGate) {
            this.recallGate = recallGate;
        }

        public double getMrr() {
            return mrr;
        }

        public void setMrr(double mrr) {
            this.mrr = mrr;
        }

        public double getMrrGate() {
            return mrrGate;
        }

        public void setMrrGate(double mrrGate) {
            this.mrrGate = mrrGate;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}

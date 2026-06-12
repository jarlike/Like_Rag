package cn.like.rag.eval;

import java.util.List;

/**
 * 检索评测请求。relevanceField 决定 qrels 的 key 语义：
 * <ul>
 *   <li>{@code "document"}（默认）：按 documentId 标注相关性，无需知道 chunk UUID，最易构建；</li>
 *   <li>{@code "chunk"}：按 chunk id 标注，更细粒度（需先知道库内 chunk id）。</li>
 * </ul>
 */
public class EvalRequest {

    private List<EvalQuery> queries;
    private int[] ks;
    private Integer topN;
    private String relevanceField = "document";

    public List<EvalQuery> getQueries() {
        return queries;
    }

    public void setQueries(List<EvalQuery> queries) {
        this.queries = queries;
    }

    public int[] getKs() {
        return ks;
    }

    public void setKs(int[] ks) {
        this.ks = ks;
    }

    public Integer getTopN() {
        return topN;
    }

    public void setTopN(Integer topN) {
        this.topN = topN;
    }

    public String getRelevanceField() {
        return relevanceField;
    }

    public void setRelevanceField(String relevanceField) {
        this.relevanceField = relevanceField;
    }
}

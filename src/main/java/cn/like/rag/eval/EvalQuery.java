package cn.like.rag.eval;

import java.util.Map;

/**
 * 单条评测样本：query 文本 + 相关性标注（qrels）。
 * qrels 的 key 取决于 {@link EvalRequest#getRelevanceField()}：
 * "chunk" 时为 chunk id，"document" 时为 documentId；value 为 0~3 相关性等级。
 */
public class EvalQuery {

    private String id;
    private String text;
    private Map<String, Integer> qrels;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Map<String, Integer> getQrels() {
        return qrels;
    }

    public void setQrels(Map<String, Integer> qrels) {
        this.qrels = qrels;
    }
}

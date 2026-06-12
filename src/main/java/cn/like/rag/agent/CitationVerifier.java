package cn.like.rag.agent;

import cn.like.rag.model.SearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用校验：检查答案中的 [n] 引用是否存在、是否落在证据编号范围内、关键结论是否带引用。
 * 对应项目书第七章 {@code CitationVerifier} 与第六章第 4 节"最终答案必须保留引用来源"。
 */
@Service
public class CitationVerifier {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");

    public Result verify(String answer, List<SearchHit> evidence) {
        return verify(answer, evidence == null ? 0 : evidence.size());
    }

    public Result verify(String answer, int evidenceCount) {
        List<Integer> cited = new ArrayList<>();
        List<Integer> invalid = new ArrayList<>();

        if (answer != null) {
            Matcher matcher = CITATION.matcher(answer);
            while (matcher.find()) {
                int n = Integer.parseInt(matcher.group(1));
                if (!cited.contains(n)) {
                    cited.add(n);
                }
                if (n < 1 || n > evidenceCount) {
                    if (!invalid.contains(n)) {
                        invalid.add(n);
                    }
                }
            }
        }

        boolean hasCitation = !cited.isEmpty();
        boolean noInvalid = invalid.isEmpty();
        boolean insufficient = answer != null
                && (answer.contains("证据不足") || answer.contains("不足以回答") || answer.contains("没有检索到"));
        // 通过条件：要么明确声明证据不足，要么至少有一个有效引用且不存在越界引用。
        boolean passed = insufficient || (hasCitation && noInvalid);

        Result result = new Result();
        result.setPassed(passed);
        result.setHasCitation(hasCitation);
        result.setCitedIndices(cited);
        result.setInvalidIndices(invalid);
        result.setEvidenceCount(evidenceCount);
        result.setDeclaredInsufficient(insufficient);
        result.setMessage(buildMessage(passed, hasCitation, invalid, insufficient, evidenceCount));
        return result;
    }

    private String buildMessage(boolean passed, boolean hasCitation, List<Integer> invalid,
                                boolean insufficient, int evidenceCount) {
        if (insufficient) {
            return "答案已明确声明证据不足，视为通过。";
        }
        if (!hasCitation) {
            return "答案缺少 [n] 引用，关键结论未绑定来源。";
        }
        if (!invalid.isEmpty()) {
            return "答案存在越界引用 " + invalid + "，证据仅有 " + evidenceCount + " 条。";
        }
        return passed ? "引用完整且均在证据范围内。" : "引用校验未通过。";
    }

    public static class Result {
        private boolean passed;
        private boolean hasCitation;
        private boolean declaredInsufficient;
        private List<Integer> citedIndices = new ArrayList<>();
        private List<Integer> invalidIndices = new ArrayList<>();
        private int evidenceCount;
        private String message;

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }

        public boolean isHasCitation() {
            return hasCitation;
        }

        public void setHasCitation(boolean hasCitation) {
            this.hasCitation = hasCitation;
        }

        public boolean isDeclaredInsufficient() {
            return declaredInsufficient;
        }

        public void setDeclaredInsufficient(boolean declaredInsufficient) {
            this.declaredInsufficient = declaredInsufficient;
        }

        public List<Integer> getCitedIndices() {
            return citedIndices;
        }

        public void setCitedIndices(List<Integer> citedIndices) {
            this.citedIndices = citedIndices;
        }

        public List<Integer> getInvalidIndices() {
            return invalidIndices;
        }

        public void setInvalidIndices(List<Integer> invalidIndices) {
            this.invalidIndices = invalidIndices;
        }

        public int getEvidenceCount() {
            return evidenceCount;
        }

        public void setEvidenceCount(int evidenceCount) {
            this.evidenceCount = evidenceCount;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}

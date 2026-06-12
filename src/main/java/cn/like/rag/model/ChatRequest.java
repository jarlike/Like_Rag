package cn.like.rag.model;

import javax.validation.constraints.NotBlank;

public class ChatRequest {

    @NotBlank(message = "question is required")
    private String question;
    private Integer topK;
    /** 多轮对话会话 ID。为空则按独立单轮处理；复用同一 ID 即可延续上下文。 */
    private String sessionId;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}

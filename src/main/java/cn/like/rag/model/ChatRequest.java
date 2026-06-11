package cn.like.rag.model;

import javax.validation.constraints.NotBlank;

public class ChatRequest {

    @NotBlank(message = "question is required")
    private String question;
    private Integer topK;

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
}

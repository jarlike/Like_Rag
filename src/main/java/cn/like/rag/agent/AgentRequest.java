package cn.like.rag.agent;

import javax.validation.constraints.NotBlank;

public class AgentRequest {

    @NotBlank(message = "must not be blank")
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

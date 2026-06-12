package cn.like.rag.agent;

import cn.like.rag.model.ChatResponse;
import cn.like.rag.model.SearchHit;

import java.util.List;

/**
 * Agent 问答响应：在普通 RAG 回答之上，额外暴露计划、执行步骤、引用校验结果与资源消耗，
 * 便于前端展示"计划 + 检索 + 校验 + 生成"的多步过程。
 */
public class AgentResponse {

    private String traceId;
    private String question;
    private String answer;
    private String provider;
    private String model;
    private List<String> plan;
    private List<AgentStep> steps;
    private List<SearchHit> hits;
    private List<ChatResponse.Citation> citations;
    private CitationVerifier.Result citationCheck;
    private String stopReason;
    private int toolCalls;
    private long elapsedMillis;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<String> getPlan() {
        return plan;
    }

    public void setPlan(List<String> plan) {
        this.plan = plan;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentStep> steps) {
        this.steps = steps;
    }

    public List<SearchHit> getHits() {
        return hits;
    }

    public void setHits(List<SearchHit> hits) {
        this.hits = hits;
    }

    public List<ChatResponse.Citation> getCitations() {
        return citations;
    }

    public void setCitations(List<ChatResponse.Citation> citations) {
        this.citations = citations;
    }

    public CitationVerifier.Result getCitationCheck() {
        return citationCheck;
    }

    public void setCitationCheck(CitationVerifier.Result citationCheck) {
        this.citationCheck = citationCheck;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(int toolCalls) {
        this.toolCalls = toolCalls;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public void setElapsedMillis(long elapsedMillis) {
        this.elapsedMillis = elapsedMillis;
    }
}

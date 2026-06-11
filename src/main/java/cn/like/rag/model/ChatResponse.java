package cn.like.rag.model;

import java.util.List;

public class ChatResponse {

    private String question;
    private String answer;
    private List<Citation> citations;
    private List<SearchHit> hits;

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

    public List<Citation> getCitations() {
        return citations;
    }

    public void setCitations(List<Citation> citations) {
        this.citations = citations;
    }

    public List<SearchHit> getHits() {
        return hits;
    }

    public void setHits(List<SearchHit> hits) {
        this.hits = hits;
    }

    public static class Citation {
        private String documentId;
        private String documentName;
        private int chunkIndex;
        private String sectionPath;
        private double score;
        private String snippet;

        public String getDocumentId() {
            return documentId;
        }

        public void setDocumentId(String documentId) {
            this.documentId = documentId;
        }

        public String getDocumentName() {
            return documentName;
        }

        public void setDocumentName(String documentName) {
            this.documentName = documentName;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public void setChunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        public String getSectionPath() {
            return sectionPath;
        }

        public void setSectionPath(String sectionPath) {
            this.sectionPath = sectionPath;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getSnippet() {
            return snippet;
        }

        public void setSnippet(String snippet) {
            this.snippet = snippet;
        }
    }
}

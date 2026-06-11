package cn.like.rag.model;

public class SearchHit {

    private RagChunk chunk;
    private double score;

    public SearchHit() {
    }

    public SearchHit(RagChunk chunk, double score) {
        this.chunk = chunk;
        this.score = score;
    }

    public RagChunk getChunk() {
        return chunk;
    }

    public void setChunk(RagChunk chunk) {
        this.chunk = chunk;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}

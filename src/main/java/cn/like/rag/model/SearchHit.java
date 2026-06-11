package cn.like.rag.model;

public class SearchHit {

    private RagChunk chunk;
    private double score;
    private int denseRank = -1;
    private int sparseRank = -1;
    private double denseScore;
    private double sparseScore;
    private double rrfScore;

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

    public int getDenseRank() {
        return denseRank;
    }

    public void setDenseRank(int denseRank) {
        this.denseRank = denseRank;
    }

    public int getSparseRank() {
        return sparseRank;
    }

    public void setSparseRank(int sparseRank) {
        this.sparseRank = sparseRank;
    }

    public double getDenseScore() {
        return denseScore;
    }

    public void setDenseScore(double denseScore) {
        this.denseScore = denseScore;
    }

    public double getSparseScore() {
        return sparseScore;
    }

    public void setSparseScore(double sparseScore) {
        this.sparseScore = sparseScore;
    }

    public double getRrfScore() {
        return rrfScore;
    }

    public void setRrfScore(double rrfScore) {
        this.rrfScore = rrfScore;
    }
}

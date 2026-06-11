package cn.like.rag.model;

public class IndexMessage {

    private String documentId;
    private boolean rebuild;

    public IndexMessage() {
    }

    public IndexMessage(String documentId, boolean rebuild) {
        this.documentId = documentId;
        this.rebuild = rebuild;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public boolean isRebuild() {
        return rebuild;
    }

    public void setRebuild(boolean rebuild) {
        this.rebuild = rebuild;
    }
}

package cn.like.rag.agent.tool;

import cn.like.rag.model.RagChunk;
import cn.like.rag.model.RagDocument;
import cn.like.rag.service.DocumentService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ReAct 工具：查看文档列表或某文档的 chunk 元数据。用于了解知识库里有哪些文档、判断证据是否齐全。
 */
@Component
public class DocumentLookupTool implements AgentTool {

    private final DocumentService documentService;

    public DocumentLookupTool(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public String name() {
        return "document_lookup";
    }

    @Override
    public String description() {
        return "查看知识库文档与切片元数据。不带参数 {} 返回文档列表；"
                + "带 {\"documentId\": \"xxx\"} 返回该文档的 chunk 概览。";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String documentId = ToolArgs.asString(args, "documentId");
        if (documentId == null || documentId.isBlank()) {
            List<RagDocument> docs = documentService.list();
            if (docs.isEmpty()) {
                return ToolResult.text("知识库暂无文档。");
            }
            StringBuilder sb = new StringBuilder("知识库共有 " + docs.size() + " 个文档：\n");
            for (RagDocument doc : docs) {
                sb.append("- id=").append(doc.getId())
                        .append(" | name=").append(doc.getFileName())
                        .append(" | status=").append(doc.getStatus())
                        .append(" | chunks=").append(doc.getChunkCount())
                        .append("\n");
            }
            return ToolResult.text(sb.toString().trim());
        }

        List<RagChunk> chunks = documentService.chunks(documentId);
        if (chunks.isEmpty()) {
            return ToolResult.text("文档 " + documentId + " 不存在或没有 chunk。");
        }
        StringBuilder sb = new StringBuilder("文档 " + documentId + " 共有 " + chunks.size() + " 个 chunk：\n");
        int limit = Math.min(chunks.size(), 20);
        for (int i = 0; i < limit; i++) {
            RagChunk chunk = chunks.get(i);
            sb.append("- #").append(chunk.getChunkIndex())
                    .append(" | section=").append(chunk.getSectionPath())
                    .append(" | tokens=").append(chunk.getTokenCount())
                    .append(" | ").append(snippet(chunk.getText(), 80))
                    .append("\n");
        }
        if (chunks.size() > limit) {
            sb.append("...（其余 ").append(chunks.size() - limit).append(" 个略）");
        }
        return ToolResult.text(sb.toString().trim());
    }

    private String snippet(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }
}

package cn.like.rag.repository;

import cn.like.rag.model.DocumentStatus;
import cn.like.rag.model.RagDocument;
import cn.like.rag.util.PostgresTextSanitizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<RagDocument> rowMapper = (rs, rowNum) -> {
        RagDocument document = new RagDocument();
        document.setId(rs.getString("id"));
        document.setFileName(rs.getString("file_name"));
        document.setContentType(rs.getString("content_type"));
        document.setSize(rs.getLong("size"));
        document.setStoragePath(rs.getString("storage_path"));
        document.setStatus(DocumentStatus.valueOf(rs.getString("status")));
        document.setChunkCount(rs.getInt("chunk_count"));
        document.setErrorMessage(rs.getString("error_message"));
        document.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        document.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        document.setIndexedAt(toLocalDateTime(rs.getTimestamp("indexed_at")));
        return document;
    };

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RagDocument save(RagDocument document) {
        jdbcTemplate.update(
                "INSERT INTO rag_documents (" +
                        "id, file_name, content_type, size, storage_path, status, " +
                        "chunk_count, error_message, created_at, updated_at, indexed_at" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (id) DO UPDATE SET " +
                "file_name = EXCLUDED.file_name, " +
                        "content_type = EXCLUDED.content_type, " +
                        "size = EXCLUDED.size, " +
                        "storage_path = EXCLUDED.storage_path, " +
                        "status = EXCLUDED.status, " +
                        "chunk_count = EXCLUDED.chunk_count, " +
                        "error_message = EXCLUDED.error_message, " +
                        "created_at = EXCLUDED.created_at, " +
                        "updated_at = EXCLUDED.updated_at, " +
                        "indexed_at = EXCLUDED.indexed_at",
                document.getId(),
                PostgresTextSanitizer.clean(document.getFileName()),
                PostgresTextSanitizer.clean(document.getContentType()),
                document.getSize(),
                PostgresTextSanitizer.clean(document.getStoragePath()),
                document.getStatus().name(),
                document.getChunkCount(),
                PostgresTextSanitizer.cleanAndLimit(document.getErrorMessage(), 4000),
                toTimestamp(document.getCreatedAt()),
                toTimestamp(document.getUpdatedAt()),
                toTimestamp(document.getIndexedAt()));
        return document;
    }

    public Optional<RagDocument> findById(String id) {
        return jdbcTemplate.query(
                        "SELECT id, file_name, content_type, size, storage_path, status, " +
                                "chunk_count, error_message, created_at, updated_at, indexed_at " +
                                "FROM rag_documents WHERE id = ?",
                rowMapper,
                id)
                .stream()
                .findFirst();
    }

    public List<RagDocument> findAll() {
        return jdbcTemplate.query(
                "SELECT id, file_name, content_type, size, storage_path, status, " +
                        "chunk_count, error_message, created_at, updated_at, indexed_at " +
                        "FROM rag_documents ORDER BY created_at DESC NULLS LAST",
                rowMapper);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM rag_documents WHERE id = ?", id);
    }

    private static Timestamp toTimestamp(LocalDateTime dateTime) {
        return dateTime == null ? null : Timestamp.valueOf(dateTime);
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

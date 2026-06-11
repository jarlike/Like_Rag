package cn.like.rag.repository;

import cn.like.rag.model.RagChunk;
import cn.like.rag.model.SearchHit;
import cn.like.rag.util.PostgresTextSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class ChunkRepository {

    private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChunkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<RagChunk> replaceDocumentChunks(String documentId, List<RagChunk> newChunks) {
        deleteByDocumentId(documentId);
        jdbcTemplate.batchUpdate(
                "INSERT INTO rag_chunks (" +
                        "id, document_id, document_name, chunk_index, text, embedding, " +
                        "section_path, token_count, metadata, created_at, search_text, search_vector" +
                        ") VALUES (?, ?, ?, ?, ?, CAST(? AS vector), ?, ?, CAST(? AS jsonb), ?, ?, to_tsvector('simple', ?))",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        RagChunk chunk = newChunks.get(i);
                        String searchText = PostgresTextSanitizer.clean(chunk.getSearchText());
                        ps.setString(1, PostgresTextSanitizer.clean(chunk.getId()));
                        ps.setString(2, PostgresTextSanitizer.clean(chunk.getDocumentId()));
                        ps.setString(3, PostgresTextSanitizer.clean(chunk.getDocumentName()));
                        ps.setInt(4, chunk.getChunkIndex());
                        ps.setString(5, PostgresTextSanitizer.clean(chunk.getText()));
                        ps.setString(6, toVectorLiteral(chunk.getVector()));
                        ps.setString(7, PostgresTextSanitizer.clean(chunk.getSectionPath()));
                        ps.setInt(8, chunk.getTokenCount());
                        ps.setString(9, toJson(chunk.getMetadata()));
                        ps.setTimestamp(10, toTimestamp(chunk.getCreatedAt()));
                        ps.setString(11, searchText);
                        ps.setString(12, searchText == null ? "" : searchText);
                    }

                    @Override
                    public int getBatchSize() {
                        return newChunks.size();
                    }
                });
        return newChunks;
    }

    public List<RagChunk> findAll() {
        return jdbcTemplate.query(
                "SELECT id, document_id, document_name, chunk_index, text, " +
                        "embedding::text AS embedding, section_path, token_count, " +
                        "metadata::text AS metadata, created_at " +
                        "FROM rag_chunks ORDER BY created_at DESC NULLS LAST",
                chunkRowMapper());
    }

    public List<RagChunk> findByDocumentId(String documentId) {
        return jdbcTemplate.query(
                "SELECT id, document_id, document_name, chunk_index, text, " +
                        "embedding::text AS embedding, section_path, token_count, " +
                        "metadata::text AS metadata, created_at " +
                        "FROM rag_chunks WHERE document_id = ? ORDER BY chunk_index ASC",
                chunkRowMapper(),
                documentId);
    }

    public List<SearchHit> search(double[] queryVector, int limit) {
        String vectorLiteral = toVectorLiteral(queryVector);
        return jdbcTemplate.query(
                "SELECT id, document_id, document_name, chunk_index, text, " +
                        "embedding::text AS embedding, section_path, token_count, " +
                        "metadata::text AS metadata, created_at, " +
                        "1 - (embedding <=> CAST(? AS vector)) AS score " +
                        "FROM rag_chunks WHERE embedding IS NOT NULL " +
                        "ORDER BY embedding <=> CAST(? AS vector) LIMIT ?",
                (rs, rowNum) -> new SearchHit(chunkRowMapper().mapRow(rs, rowNum), rs.getDouble("score")),
                vectorLiteral,
                vectorLiteral,
                limit);
    }

    /**
     * 稀疏（全文）检索：tsQuery 为已构造好的 to_tsquery 表达式（由 service 层用 EmbeddingService.tokenize
     * 展开并 OR 连接），按 ts_rank_cd 降序返回命中。
     */
    public List<SearchHit> searchSparse(String tsQuery, int limit) {
        if (tsQuery == null || tsQuery.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT id, document_id, document_name, chunk_index, text, " +
                        "embedding::text AS embedding, section_path, token_count, " +
                        "metadata::text AS metadata, created_at, " +
                        "ts_rank_cd(search_vector, to_tsquery('simple', ?)) AS score " +
                        "FROM rag_chunks " +
                        "WHERE search_vector @@ to_tsquery('simple', ?) " +
                        "ORDER BY score DESC LIMIT ?",
                (rs, rowNum) -> new SearchHit(chunkRowMapper().mapRow(rs, rowNum), rs.getDouble("score")),
                tsQuery,
                tsQuery,
                limit);
    }

    public void deleteByDocumentId(String documentId) {
        jdbcTemplate.update("DELETE FROM rag_chunks WHERE document_id = ?", documentId);
    }

    private RowMapper<RagChunk> chunkRowMapper() {
        return (rs, rowNum) -> {
            RagChunk chunk = new RagChunk();
            chunk.setId(rs.getString("id"));
            chunk.setDocumentId(rs.getString("document_id"));
            chunk.setDocumentName(rs.getString("document_name"));
            chunk.setChunkIndex(rs.getInt("chunk_index"));
            chunk.setText(rs.getString("text"));
            chunk.setVector(fromVectorLiteral(rs.getString("embedding")));
            chunk.setSectionPath(rs.getString("section_path"));
            chunk.setTokenCount(rs.getInt("token_count"));
            chunk.setMetadata(fromJson(rs.getString("metadata")));
            chunk.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
            return chunk;
        };
    }

    private String toVectorLiteral(double[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(String.format(Locale.ROOT, "%.9f", vector[i]));
        }
        return literal.append(']').toString();
    }

    private double[] fromVectorLiteral(String literal) {
        if (literal == null || literal.length() < 2) {
            return new double[0];
        }
        String body = literal.substring(1, literal.length() - 1);
        if (body.isBlank()) {
            return new double[0];
        }
        String[] parts = body.split(",");
        double[] vector = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Double.parseDouble(parts[i]);
        }
        return vector;
    }

    private String toJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(PostgresTextSanitizer.cleanMap(metadata));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chunk metadata", e);
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse chunk metadata", e);
        }
    }

    private static Timestamp toTimestamp(LocalDateTime dateTime) {
        return dateTime == null ? null : Timestamp.valueOf(dateTime);
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

package cn.like.rag.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Locale;

@Component
public class PgVectorSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;
    private final RagProperties properties;

    public PgVectorSchemaInitializer(JdbcTemplate jdbcTemplate, RagProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        int dimension = Math.max(1, properties.getEmbeddingDimension());
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS rag_documents (" +
                        "id TEXT PRIMARY KEY, " +
                        "file_name TEXT NOT NULL, " +
                        "content_type TEXT, " +
                        "size BIGINT NOT NULL, " +
                        "storage_path TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "chunk_count INTEGER NOT NULL DEFAULT 0, " +
                        "error_message TEXT, " +
                        "created_at TIMESTAMP, " +
                        "updated_at TIMESTAMP, " +
                        "indexed_at TIMESTAMP" +
                        ")");
        jdbcTemplate.execute(String.format(Locale.ROOT,
                "CREATE TABLE IF NOT EXISTS rag_chunks (" +
                        "id TEXT PRIMARY KEY, " +
                        "document_id TEXT NOT NULL, " +
                        "document_name TEXT NOT NULL, " +
                        "chunk_index INTEGER NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "embedding vector(%d), " +
                        "section_path TEXT, " +
                        "token_count INTEGER NOT NULL DEFAULT 0, " +
                        "metadata JSONB, " +
                        "created_at TIMESTAMP" +
                        ")", dimension));
        jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS section_path TEXT");
        jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS token_count INTEGER");
        jdbcTemplate.execute("UPDATE rag_chunks SET token_count = 0 WHERE token_count IS NULL");
        jdbcTemplate.execute("ALTER TABLE rag_chunks ALTER COLUMN token_count SET DEFAULT 0");
        jdbcTemplate.execute("ALTER TABLE rag_chunks ALTER COLUMN token_count SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS metadata JSONB");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunks_document_id ON rag_chunks(document_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunks_document_order ON rag_chunks(document_id, chunk_index)");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_rag_chunks_embedding_hnsw " +
                        "ON rag_chunks USING hnsw (embedding vector_cosine_ops) " +
                        "WITH (m = 16, ef_construction = 64)");

        // Hybrid Search 稀疏检索字段与索引（项目书第一阶段）
        jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS search_text TEXT");
        jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS search_vector tsvector");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_rag_chunks_search_vector_gin " +
                        "ON rag_chunks USING gin(search_vector)");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_rag_chunks_search_text_trgm " +
                        "ON rag_chunks USING gin(search_text gin_trgm_ops)");
    }
}

package com.zl.d32.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库能力验收：通过应用实际使用的 {@code pgDataSource} 执行纯 JDBC 验证。
 *
 * <p>本测试不依赖 Spring AI VectorStore 的封装，直接覆盖任务书要求的数据库层能力：
 * PostgreSQL 版本、pgvector 扩展、独立测试表的 {@code vector(384)}、测试向量写入/读取、
 * cosine 距离 {@code <=>}、HNSW 索引与错误维度拒绝。</p>
 *
 * <p>显式门控：仅当 {@code D32_PG_IT=true} 时执行，普通测试套件不会连接外部 PostgreSQL。
 * 连接口令来自环境变量 {@code PG_PASSWORD}，不得写入仓库。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "D32_PG_IT", matches = "true")
class PgVectorJdbcAcceptanceTest {

    private static final String TABLE = "public.d32_pgvector_test";

    @Autowired
    @Qualifier("pgDataSource")
    private DataSource pgDataSource;

    @Autowired
    private EmbeddingModel embeddingModel;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(pgDataSource);
    }

    @Test
    @DisplayName("JDBC 连接成功，PostgreSQL 18.7 与 pgvector 0.8.7 可用")
    void postgresVersionAndVectorExtension() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        String version = jdbc.queryForObject("SELECT version()", String.class);
        String extension = jdbc.queryForObject(
                "SELECT extname || '|' || extversion FROM pg_extension WHERE extname = 'vector'",
                String.class);

        assertThat(database).isEqualTo("spring_ai");
        assertThat(version).contains("PostgreSQL 18.7");
        assertThat(extension).isEqualTo("vector|0.8.7");
    }

    @Test
    @DisplayName("Embedding 实际输出与测试表列均为 384 维")
    void embeddingAndColumnAre384Dimensions() {
        ensureTestTable();
        float[] vector = embeddingModel.embed("D32 向量维度验证文本");
        String columnType = jdbc.queryForObject(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                        + "WHERE attrelid = ?::regclass AND attname = 'embedding'",
                String.class, TABLE);

        assertThat(vector).hasSize(384);
        assertThat(columnType).isEqualTo("vector(384)");
    }

    @Test
    @DisplayName("测试向量真实写入并可读取 vector_dims=384")
    void insertAndReadSyntheticVector() {
        ensureTestTable();
        String marker = "D32-JDBC-READ-" + UUID.randomUUID();
        String vector = vectorLiteral(384, 0.1f, 0.1f);

        jdbc.update("INSERT INTO " + TABLE + " (content, embedding) VALUES (?, ?::vector)", marker, vector);

        Integer dimensions = jdbc.queryForObject(
                "SELECT vector_dims(embedding) FROM " + TABLE + " WHERE content = ?",
                Integer.class, marker);
        String storedContent = jdbc.queryForObject(
                "SELECT content FROM " + TABLE + " WHERE content = ?",
                String.class, marker);

        assertThat(dimensions).isEqualTo(384);
        assertThat(storedContent).isEqualTo(marker);
    }

    @Test
    @DisplayName("cosine 距离 <=> 可执行且按距离升序返回")
    void cosineDistanceQueryOrdersByDistance() {
        ensureTestTable();
        String suffix = UUID.randomUUID().toString();
        String contentA = "D32-JDBC-COSINE-A-" + suffix;
        String contentB = "D32-JDBC-COSINE-B-" + suffix;
        String vectorA = vectorLiteral(384, 0.1f, 0.1f);
        String vectorB = vectorLiteral(384, 1.0f, 0.0f);

        jdbc.update("INSERT INTO " + TABLE + " (content, embedding) VALUES (?, ?::vector)", contentA, vectorA);
        jdbc.update("INSERT INTO " + TABLE + " (content, embedding) VALUES (?, ?::vector)", contentB, vectorB);

        List<Map<String, Object>> ranked = jdbc.queryForList(
                "SELECT content, embedding <=> ?::vector AS cosine_distance FROM " + TABLE
                        + " WHERE content IN (?, ?) ORDER BY embedding <=> ?::vector LIMIT 5",
                vectorA, contentA, contentB, vectorA);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).get("content")).isEqualTo(contentA);
        float firstDistance = ((Number) ranked.get(0).get("cosine_distance")).floatValue();
        float secondDistance = ((Number) ranked.get(1).get("cosine_distance")).floatValue();
        assertThat(firstDistance).isBetween(0.0f, 2.0f);
        assertThat(secondDistance).isBetween(0.0f, 2.0f);
        assertThat(firstDistance).isLessThan(secondDistance);
    }

    @Test
    @DisplayName("HNSW vector_cosine_ops 索引存在并可输出 EXPLAIN")
    void hnswIndexAndExplain() {
        ensureTestTable();
        List<String> indexDefinitions = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND tablename = 'd32_pgvector_test' "
                        + "AND indexname = 'idx_d32_pgvector_test_embedding'",
                String.class);

        assertThat(indexDefinitions).hasSize(1);
        assertThat(indexDefinitions.get(0)).containsIgnoringCase("using hnsw");
        assertThat(indexDefinitions.get(0)).contains("vector_cosine_ops");

        String queryVector = vectorLiteral(384, 0.1f, 0.1f);
        List<String> plan = jdbc.queryForList(
                "EXPLAIN SELECT id, content, embedding <=> ?::vector AS distance FROM " + TABLE
                        + " ORDER BY embedding <=> ?::vector LIMIT 5",
                String.class, queryVector, queryVector);
        assertThat(plan).isNotEmpty();
        System.out.println("[D32][EXPLAIN] " + String.join(" | ", plan));
    }

    @Test
    @DisplayName("383 维向量被 vector(384) 拒绝且不产生数据")
    void wrongDimensionIsRejected() {
        ensureTestTable();
        String marker = "D32-JDBC-INVALID-" + UUID.randomUUID();
        String invalidVector = vectorLiteral(383, 0.1f, 0.1f);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO " + TABLE + " (content, embedding) VALUES (?, ?::vector)", marker, invalidVector))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("expected 384 dimensions, not 383");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM " + TABLE + " WHERE content = ?", Integer.class, marker);
        assertThat(rows).isZero();
    }

    /**
     * 专用测试表不存在时创建；已存在时只校验结构，不覆盖、不删除既有数据。
     */
    private void ensureTestTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "content TEXT NOT NULL, "
                + "embedding VECTOR(384) NOT NULL, "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_d32_pgvector_test_embedding "
                + "ON " + TABLE + " USING hnsw (embedding vector_cosine_ops)");

        String columnType = jdbc.queryForObject(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                        + "WHERE attrelid = ?::regclass AND attname = 'embedding'",
                String.class, TABLE);
        assertThat(columnType)
                .as("既有 d32_pgvector_test 表结构必须为 vector(384)，不得静默覆盖不兼容表")
                .isEqualTo("vector(384)");
    }

    private static String vectorLiteral(int dimensions, float first, float rest) {
        StringBuilder builder = new StringBuilder(dimensions * 4).append('[');
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(i == 0 ? first : rest);
        }
        return builder.append(']').toString();
    }
}

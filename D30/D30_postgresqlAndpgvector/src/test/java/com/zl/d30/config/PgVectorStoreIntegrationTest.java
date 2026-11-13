package com.zl.d30.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector 真机集成测试（显式门控，默认不执行）。
 *
 * <p>门控条件：环境变量 {@code D30_PG_IT=true}。普通 {@code mvn test} 不会连接 PostgreSQL，
 * 避免测试套件对外部数据库产生硬依赖；需要真机验收时显式开启：</p>
 *
 * <pre>
 * $env:D30_PG_IT="true"; mvn -f D30\pom.xml test -Dtest=PgVectorStoreIntegrationTest
 * </pre>
 *
 * <p>覆盖点：真实建表列类型 vector(384)、HNSW 索引、写入 + 相似度检索、双数据源隔离。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.vectorstore.pgvector.initialize-schema=true",
                "spring.ai.vectorstore.pgvector.schema-validation=false"
        })
@EnabledIfEnvironmentVariable(named = "D30_PG_IT", matches = "true")
class PgVectorStoreIntegrationTest {

    /** 主键类型为 UUID（PgIdType.UUID），写入时必须使用合法 UUID 字符串。 */
    private static final String DOC_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Autowired
    private PgVectorStore vectorStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    @Qualifier("pgDataSource")
    private DataSource pgDataSource;

    @Autowired
    private DataSource primaryDataSource;

    @AfterEach
    void cleanUp() {
        vectorStore.delete(List.of(DOC_ID));
    }

    @Test
    @DisplayName("建表列类型为 vector(384)")
    void embeddingColumnIsVector384() {
        JdbcTemplate jdbc = new JdbcTemplate(pgDataSource);
        String columnType = jdbc.queryForObject(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                        + "WHERE attrelid = 'public.vector_store'::regclass AND attname = 'embedding'",
                String.class);
        assertThat(columnType).isEqualTo("vector(384)");
    }

    @Test
    @DisplayName("向量索引为 HNSW")
    void vectorIndexIsHnsw() {
        JdbcTemplate jdbc = new JdbcTemplate(pgDataSource);
        List<String> indexDefs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'vector_store'",
                String.class);
        assertThat(indexDefs).isNotEmpty();
        assertThat(indexDefs.stream().anyMatch(def -> def.contains("hnsw"))).isTrue();
    }

    @Test
    @DisplayName("写入 384 维向量并可相似度检索命中")
    void addAndSimilaritySearch() {
        Document document = new Document(DOC_ID,
                "设备运行中出现异常振动，应先检查联轴器同轴度与轴承间隙。",
                Map.of("source", "d30-it"));

        vectorStore.add(List.of(document));

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("设备异常振动如何排查")
                        .topK(3)
                        .similarityThresholdAll()
                        .build());

        assertThat(results).isNotEmpty();
        assertThat(results.stream().map(Document::getId)).contains(DOC_ID);
        assertThat(results.get(0).getText()).contains("异常振动");

        // 落库侧实证：数据库中该行的向量维度必须是 384
        JdbcTemplate jdbc = new JdbcTemplate(pgDataSource);
        Integer storedDims = jdbc.queryForObject(
                "SELECT vector_dims(embedding) FROM public.vector_store WHERE id = ?::uuid",
                Integer.class, DOC_ID);
        assertThat(storedDims).isEqualTo(384);
    }

    @Test
    @DisplayName("Embedding 模型实际输出维度为 384")
    void embeddingModelOutputIs384() {
        float[] vector = embeddingModel.embed("设备运行中出现异常振动，应当如何排查？");
        assertThat(vector).hasSize(384);
    }

    @Test
    @DisplayName("PG 池与 MySQL 主库完全隔离")
    void poolsAreIsolated() {
        assertThat(pgDataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource pg = (HikariDataSource) pgDataSource;
        assertThat(pg.getPoolName()).isEqualTo("PgVectorHikariPool");
        assertThat(pg.getJdbcUrl()).startsWith("jdbc:postgresql://");

        assertThat(primaryDataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource mysql = (HikariDataSource) primaryDataSource;
        assertThat(mysql.getJdbcUrl()).startsWith("jdbc:mysql://");
    }
}

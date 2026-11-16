package com.zl.d33.config;

import com.zl.d33.config.PgVectorStoreConfig;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIdType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pgvector 向量库配置（命名空间 {@code spring.ai.vectorstore.pgvector}）。
 *
 * <p>属性名与 Spring AI 1.1.8 的 {@code PgVectorStoreProperties} 对齐（该类经 jar 反编译核验）：
 * {@code initialize-schema / schema-name / table-name / dimensions / index-type / distance-type /
 * schema-validation / max-document-batch-size / id-type / remove-existing-vector-store-table}。</p>
 *
 * <p>注意：1.1.8 <b>不存在</b> {@code index-list-size}、{@code index-m}、{@code batching-size} 三个属性，
 * 因此本类不声明它们；HNSW 的 m / ef_construction 采用 pgvector 服务端默认值（m=16、ef_construction=64），
 * 如需调优应在库内执行 {@code ALTER INDEX ... SET (...)}，而不是写进 YAML 造成“配置看似生效实则被忽略”。</p>
 *
 * <p>本项目显式排除 {@code PgVectorStoreAutoConfiguration}（其 vectorStore 方法依赖主 {@code JdbcTemplate}，
 * 会误用 MySQL 主库），改由 {@link PgVectorStoreConfig} 使用独立 {@code pgDataSource} 手动装配，
 * 因此该前缀的配置由本类绑定，而非 Spring AI 自动配置类。</p>
 */
@Data
@ConfigurationProperties(prefix = "spring.ai.vectorstore.pgvector")
public class PgVectorStoreProperties {

    /** 是否在启动时自动建表/建索引（首次初始化 true；表已存在后可置 false）。 */
    private boolean initializeSchema = true;

    /** PostgreSQL schema 名称。 */
    private String schemaName = "public";

    /** 向量表名称。 */
    private String tableName = "vector_store";

    /** 向量维度，全链路基线 384。 */
    private int dimensions = 384;

    /** 索引类型：HNSW（pgvector 0.8+ 推荐）/ IVFFLAT / NONE。 */
    private PgIndexType indexType = PgIndexType.HNSW;

    /** 距离度量：余弦距离。 */
    private PgDistanceType distanceType = PgDistanceType.COSINE_DISTANCE;

    /** 是否在启动时校验既有表结构与配置一致（表结构不符会 fail-fast）。 */
    private boolean schemaValidation = false;

    /** 单批写入的最大文档数，避免一次性超长事务。 */
    private int maxDocumentBatchSize = 100;

    /** 主键类型：UUID 由数据库生成，避免应用侧主键冲突。 */
    private PgIdType idType = PgIdType.UUID;

    /** 是否在启动时删除并重建向量表（危险开关，默认关闭）。 */
    private boolean removeExistingVectorStoreTable = false;

    /**
     * 启动校验：把非法配置挡在装配之前，避免运行期才暴露。
     */
    @PostConstruct
    public void validate() {
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalArgumentException("spring.ai.vectorstore.pgvector.schema-name 不能为空");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("spring.ai.vectorstore.pgvector.table-name 不能为空");
        }
        if (dimensions < 1) {
            throw new IllegalArgumentException("spring.ai.vectorstore.pgvector.dimensions 必须为正");
        }
        if (indexType == null || distanceType == null || idType == null) {
            throw new IllegalArgumentException("pgvector 的 index-type / distance-type / id-type 不能为空");
        }
        if (maxDocumentBatchSize < 1) {
            throw new IllegalArgumentException("spring.ai.vectorstore.pgvector.max-document-batch-size 必须为正");
        }
    }
}

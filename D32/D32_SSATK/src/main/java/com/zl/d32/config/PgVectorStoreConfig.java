package com.zl.d32.config;

import com.zl.d32.config.PgVectorStoreProperties;
import com.zl.d32.config.VectorDimensionGuard;
import com.zl.d32.document.embedding.config.EmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PgVectorStore 手动装配：绑定独立 {@code pgDataSource}，维度由配置统一驱动。
 *
 * <p>为什么排除自动配置（{@code PgVectorStoreAutoConfiguration}）：经 1.1.8 jar 反编译核验，
 * 其 {@code vectorStore} 方法签名为
 * {@code vectorStore(JdbcTemplate, EmbeddingModel, PgVectorStoreProperties, ...)}，
 * 取的是容器内的主 {@code JdbcTemplate}（建立在 MySQL 主数据源上）。多数据源场景下，
 * 向量表会被建到 MySQL 里直接失败，因此排除自动配置并在此显式绑定 {@code pgDataSource}。</p>
 *
 * <p>维度治理：{@code dimensions()} 取 {@code spring.ai.vectorstore.pgvector.dimensions}，
 * 装配前经 {@link VectorDimensionGuard} 与 Embedding 配置及模型实际输出三方对齐，任一不一致则启动失败。</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class PgVectorStoreConfig {

    /**
     * 向量库 Bean：JdbcTemplate 只在方法内构造（不注册为 Bean），避免与主库 JdbcTemplate 产生注入歧义。
     */
    @Bean
    public PgVectorStore pgVectorStore(@Qualifier("pgDataSource") DataSource pgDataSource,
                                       EmbeddingModel embeddingModel,
                                       EmbeddingProperties embeddingProperties,
                                       PgVectorStoreProperties pgVectorStoreProperties,
                                       VectorDimensionGuard dimensionGuard) {

        dimensionGuard.verifyAlignment(
                embeddingProperties.getDimensions(),
                pgVectorStoreProperties.getDimensions(),
                embeddingModel.dimensions());

        JdbcTemplate pgJdbcTemplate = new JdbcTemplate(pgDataSource);

        PgVectorStore vectorStore = PgVectorStore.builder(pgJdbcTemplate, embeddingModel)
                .schemaName(pgVectorStoreProperties.getSchemaName())
                .vectorTableName(pgVectorStoreProperties.getTableName())
                .idType(pgVectorStoreProperties.getIdType())
                .dimensions(pgVectorStoreProperties.getDimensions())
                .distanceType(pgVectorStoreProperties.getDistanceType())
                .indexType(pgVectorStoreProperties.getIndexType())
                .initializeSchema(pgVectorStoreProperties.isInitializeSchema())
                .vectorTableValidationsEnabled(pgVectorStoreProperties.isSchemaValidation())
                .removeExistingVectorStoreTable(pgVectorStoreProperties.isRemoveExistingVectorStoreTable())
                .maxDocumentBatchSize(pgVectorStoreProperties.getMaxDocumentBatchSize())
                .build();

        log.info("PgVectorStore 装配完成：schema={}，table={}，dimensions={}，distance={}，index={}，initializeSchema={}",
                pgVectorStoreProperties.getSchemaName(),
                pgVectorStoreProperties.getTableName(),
                pgVectorStoreProperties.getDimensions(),
                pgVectorStoreProperties.getDistanceType(),
                pgVectorStoreProperties.getIndexType(),
                pgVectorStoreProperties.isInitializeSchema());

        return vectorStore;
    }
}

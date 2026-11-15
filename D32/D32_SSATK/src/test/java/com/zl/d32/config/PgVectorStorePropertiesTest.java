package com.zl.d32.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pgvector 配置绑定类测试：默认值必须与 384 / HNSW / COSINE 基线一致。
 */
class PgVectorStorePropertiesTest {

    @Test
    @DisplayName("默认值符合 D32 基线：384 维、HNSW、余弦距离、vector_store 表")
    void defaultsMatchBaseline() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        assertDoesNotThrow(properties::validate);
        assertEquals(384, properties.getDimensions());
        assertEquals("vector_store", properties.getTableName());
        assertEquals("public", properties.getSchemaName());
        assertEquals(PgIndexType.HNSW, properties.getIndexType());
        assertEquals(PgDistanceType.COSINE_DISTANCE, properties.getDistanceType());
        assertEquals(100, properties.getMaxDocumentBatchSize());
        assertEquals(true, properties.isInitializeSchema());
        assertEquals(false, properties.isRemoveExistingVectorStoreTable());
    }

    @Test
    @DisplayName("非法维度被拒绝")
    void invalidDimensionsRejected() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setDimensions(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    @DisplayName("空表名与空 schema 被拒绝")
    void blankNamesRejected() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setTableName(" ");
        assertThrows(IllegalArgumentException.class, properties::validate);

        PgVectorStoreProperties second = new PgVectorStoreProperties();
        second.setSchemaName("");
        assertThrows(IllegalArgumentException.class, second::validate);
    }

    @Test
    @DisplayName("批大小必须为正")
    void invalidBatchSizeRejected() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setMaxDocumentBatchSize(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}

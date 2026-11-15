package com.zl.d32.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 向量维度守卫测试：三处维度必须同时相等，否则 fail-closed。
 */
class VectorDimensionGuardTest {

    private final VectorDimensionGuard guard = new VectorDimensionGuard();

    @Test
    @DisplayName("三处维度一致时通过（384 基线）")
    void alignedDimensionsPass() {
        assertDoesNotThrow(() -> guard.verifyAlignment(384, 384, 384));
    }

    @Test
    @DisplayName("向量库配置与嵌入配置不一致时立即失败")
    void storeConfigMismatchFails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.verifyAlignment(384, 1536, 384));
        assertTrue(ex.getMessage().contains("app.ai.embedding.dimensions=384"));
        assertTrue(ex.getMessage().contains("spring.ai.vectorstore.pgvector.dimensions=1536"));
    }

    @Test
    @DisplayName("模型实际输出与配置不一致时立即失败")
    void modelOutputMismatchFails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.verifyAlignment(384, 384, 256));
        assertTrue(ex.getMessage().contains("EmbeddingModel.dimensions()=256"));
    }

    @Test
    @DisplayName("错误信息包含重建提示，避免误用 ALTER 修改维度")
    void messageMentionsRecreateHint() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.verifyAlignment(384, 384, 768));
        assertTrue(ex.getMessage().contains("DROP TABLE"));
        assertEquals("向量维度不一致，已阻止启动（避免写入期 expected N dimensions 报错）", ex.getMessage().split("：")[0]);
    }
}

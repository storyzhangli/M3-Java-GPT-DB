package com.zl.d33.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 向量维度一致性守卫：把“建表维度 / 向量库配置维度 / Embedding 实际输出维度”三处对齐做成启动期硬校验。
 *
 * <p>维度不一致的后果不是“效果变差”，而是写入时直接失败：
 * {@code expected 384 dimensions, not N}。且表一旦建好，维度只能删表重建，无法 ALTER。
 * 因此这里 fail-closed：任何一处不一致立即抛 {@link IllegalStateException} 阻止应用启动，
 * 而不是等到第一条向量写入时才暴露。</p>
 */
@Slf4j
@Component
public class VectorDimensionGuard {

    /**
     * 校验三处维度是否完全一致。
     *
     * @param embeddingConfiguredDimension {@code app.ai.embedding.dimensions} 配置值
     * @param storeConfiguredDimension     {@code spring.ai.vectorstore.pgvector.dimensions} 配置值
     * @param modelActualDimension         EmbeddingModel 实际输出维度（{@code dimensions()}）
     */
    public void verifyAlignment(int embeddingConfiguredDimension,
                                int storeConfiguredDimension,
                                int modelActualDimension) {
        if (embeddingConfiguredDimension == storeConfiguredDimension
                && modelActualDimension == storeConfiguredDimension) {
            log.info("向量维度一致性校验通过：embedding 配置={}，pgvector 列配置={}，模型实际输出={}",
                    embeddingConfiguredDimension, storeConfiguredDimension, modelActualDimension);
            return;
        }
        throw new IllegalStateException("向量维度不一致，已阻止启动（避免写入期 expected N dimensions 报错）："
                + "app.ai.embedding.dimensions=" + embeddingConfiguredDimension
                + "，spring.ai.vectorstore.pgvector.dimensions=" + storeConfiguredDimension
                + "，EmbeddingModel.dimensions()=" + modelActualDimension
                + "；三处必须统一，且维度变更需 DROP TABLE 后重建");
    }
}

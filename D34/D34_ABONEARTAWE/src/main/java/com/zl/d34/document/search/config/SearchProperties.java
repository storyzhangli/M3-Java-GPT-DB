package com.zl.d34.document.search.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 相似度检索治理配置（前缀 {@code app.ai.search}）。
 *
 * <p>任务书约定：默认 Top-5、K 可调；检索质量评估完成前不设相似度阈值
 * （{@code similarityThreshold} 保持 Spring AI 默认值 0.0，
 * 由评估后的分数分布另行给出建议，不在本迭代硬编码生效）。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.ai.search")
public class SearchProperties {

    /** 默认返回条数 Top-K（任务书基线为 5）。 */
    private int defaultTopK = 5;

    /** 单次检索允许的最大 K，防止一次性拉全库。 */
    private int maxTopK = 20;

    /** 查询文本最大长度（UTF-16 字符），与入库 Embedding 的输入上限保持一致。 */
    private int maxQueryLength = 8000;

    @PostConstruct
    public void validate() {
        if (defaultTopK < 1) {
            throw new IllegalArgumentException("search.default-top-k 必须 >= 1");
        }
        if (maxTopK < defaultTopK) {
            throw new IllegalArgumentException("search.max-top-k 不能小于 default-top-k");
        }
        if (maxQueryLength < 1) {
            throw new IllegalArgumentException("search.max-query-length 必须 >= 1");
        }
    }
}

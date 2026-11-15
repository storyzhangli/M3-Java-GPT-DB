package com.zl.d32.document.vector.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量入库治理配置（前缀 {@code app.ai.vector-index}）。
 */
@Data
@ConfigurationProperties(prefix = "app.ai.vector-index")
public class VectorIndexProperties {

    /** 同一文档版本的最大尝试次数（含首次），超过后进入终态 FAILED。 */
    private int maxTaskAttempts = 5;

    /** 部分失败后的建议重试间隔（秒），仅用于 next_retry_at 展示/调度。 */
    private int retryDelaySeconds = 60;

    /** 向量 metadata 中的管理标识，用于孤儿检查时只认 自己的数据。 */
    private String managedBy = "d32";

    /** 新版本成功后是否自动清理旧版本向量与 Chunk 台账。 */
    private boolean cleanupOldVersions = true;

    @PostConstruct
    public void validate() {
        if (maxTaskAttempts < 1 || maxTaskAttempts > 100) {
            throw new IllegalArgumentException("vector-index.max-task-attempts 必须在 1..100 之间");
        }
        if (retryDelaySeconds < 0 || retryDelaySeconds > 86400) {
            throw new IllegalArgumentException("vector-index.retry-delay-seconds 必须在 0..86400 之间");
        }
        if (managedBy == null || managedBy.isBlank()) {
            throw new IllegalArgumentException("vector-index.managed-by 不能为空");
        }
    }
}

package com.zl.d32.document.vector.model;

/**
 * 向量索引任务状态。
 *
 * <p>状态机：PENDING -> INDEXING -> COMPLETED / PARTIAL / FAILED；
 * 新版本 COMPLETED 后旧版本被标记 SUPERSEDED。PARTIAL 表示部分 Chunk 写入成功，
 * 可通过重试补齐；FAILED 表示本轮无可用结果或重试次数耗尽。</p>
 */
public enum VectorIndexStatus {

    PENDING,
    INDEXING,
    COMPLETED,
    PARTIAL,
    FAILED,
    SUPERSEDED;

    public static VectorIndexStatus fromName(String name) {
        for (VectorIndexStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知向量索引状态: " + name);
    }

    public boolean terminalSuccess() {
        return this == COMPLETED;
    }

    public boolean canRetry() {
        return this == PENDING || this == PARTIAL || this == FAILED;
    }
}

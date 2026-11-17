package com.zl.d34.document.vector.model;

/**
 * 单个 Chunk 的向量索引状态。
 */
public enum VectorChunkStatus {

    PENDING,
    INDEXED,
    FAILED;

    public static VectorChunkStatus fromName(String name) {
        for (VectorChunkStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知 Chunk 索引状态: " + name);
    }
}

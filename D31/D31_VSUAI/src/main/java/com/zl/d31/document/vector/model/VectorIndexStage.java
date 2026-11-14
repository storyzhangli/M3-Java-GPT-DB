package com.zl.d31.document.vector.model;

/**
 * 向量索引处理阶段，用于失败后定位与恢复。
 */
public enum VectorIndexStage {

    REGISTER,
    EMBED_WRITE,
    VERIFY,
    CLEANUP,
    DELETE
}

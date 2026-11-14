package com.zl.d31.document.vector.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 向量 Chunk 台账领域模型：稳定 chunkId、文档归属、内容快照与索引状态。
 */
@Data
public class VectorChunkRecord {

    private Long id;

    private String documentId;

    private String version;

    private String strategy;

    /** 稳定 UUID v5，同时作为 pgvector 主键。 */
    private String chunkId;

    private int chunkIndex;

    /** Chunk 文本快照，用于 Outbox 式重放与缺失恢复。 */
    private String content;

    private String chunkContentHash;

    private int characterCount;

    /** 来源页码，逗号分隔；无页码为空串。 */
    private String pageNumbers;

    private VectorChunkStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

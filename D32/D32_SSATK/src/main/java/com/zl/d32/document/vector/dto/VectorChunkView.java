package com.zl.d32.document.vector.dto;

import lombok.Data;

/**
 * Chunk 向量台账视图：不返回向量本体，避免大响应与敏感数据扩散。
 */
@Data
public class VectorChunkView {

    private String documentId;

    private String version;

    private String strategy;

    private String chunkId;

    private int chunkIndex;

    private String chunkContentHash;

    private int characterCount;

    private String pageNumbers;

    private String status;

    private String contentPreview;
}

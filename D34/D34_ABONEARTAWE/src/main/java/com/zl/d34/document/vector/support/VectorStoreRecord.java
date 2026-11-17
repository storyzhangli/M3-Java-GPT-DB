package com.zl.d34.document.vector.support;

import lombok.Data;

import java.util.Map;

/**
 * 待写入 pgvector 的单条记录。
 *
 * <p>向量已由现有 DocumentEmbeddingService 计算完成，本对象只负责
 * 把向量、内容和可追溯 metadata 写入 Spring AI 管理的 vector_store 表，
 * 不重复调用 EmbeddingModel。</p>
 */
@Data
public class VectorStoreRecord {

    private String id;

    private String content;

    private Map<String, Object> metadata;

    private float[] embedding;
}

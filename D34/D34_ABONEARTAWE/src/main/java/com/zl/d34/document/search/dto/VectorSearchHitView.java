package com.zl.d34.document.search.dto;

import lombok.Data;

import java.util.Map;

/**
 * 单条相似度检索命中。
 *
 * <p>分数语义（经 1.1.8 PgVectorStore 字节码核验）：</p>
 * <ul>
 *   <li>{@code score} = 1.0 - cosine distance，即余弦相似度（越大越相似，区间约 0..1）；</li>
 *   <li>{@code cosineDistance} 为 SQL {@code embedding <=> ?} 的原始余弦距离（越小越相似）。</li>
 * </ul>
 */
@Data
public class VectorSearchHitView {

    /** 结果序号（1 起，按相似度降序）。 */
    private int rank;

    /** 命中片段全文。 */
    private String content;

    /** 余弦相似度 = 1 - cosine distance（PgVectorStore 返回的真实分数）。 */
    private Double score;

    /** 原始余弦距离（embedding <=> ?），来自 Document metadata 的 distance 键。 */
    private Double cosineDistance;

    /** 向量记录主键（stable chunkId）。 */
    private String vectorId;

    /** 文档稳定 ID。 */
    private String documentId;

    /** 文档版本。 */
    private String version;

    /** 文档标题。 */
    private String title;

    /** 来源标识。 */
    private String source;

    /** 切片序号。 */
    private Integer chunkIndex;

    /** 模型名（metadata modelName）。 */
    private String modelName;

    /** 向量维度（metadata dimensions）。 */
    private Integer dimensions;

    /** 原始 metadata 透传（供评估与溯源，不构造字段）。 */
    private Map<String, Object> metadata;
}

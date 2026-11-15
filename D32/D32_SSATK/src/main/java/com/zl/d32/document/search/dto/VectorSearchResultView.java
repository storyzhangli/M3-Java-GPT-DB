package com.zl.d32.document.search.dto;

import lombok.Data;

import java.util.List;

/**
 * 相似度检索结果视图：请求回显 + 检索执行元信息 + 命中列表。
 */
@Data
public class VectorSearchResultView {

    /** 实际使用的查询文本（trim 后）。 */
    private String query;

    /** 实际生效的 K。 */
    private int topK;

    /** 实际生效的 metadata 过滤表达式（Spring AI Filter 转换后的 SQL 片段，便于证据留存；无过滤为 null）。 */
    private String appliedFilterExpression;

    /** 查询向量维度（Query Embedding 实际输出）。 */
    private int queryDimensions;

    /** 检索总耗时（毫秒，含 Query Embedding 与 SQL 查询）。 */
    private long elapsedMs;

    /** 命中条数。 */
    private int hitCount;

    /** 命中列表（按相似度降序）。 */
    private List<VectorSearchHitView> hits;
}

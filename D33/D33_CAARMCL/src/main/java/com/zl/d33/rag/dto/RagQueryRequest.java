package com.zl.d33.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RAG 问答请求。
 *
 * <p>{@code topK} 为空取 {@code app.ai.rag.default-top-k}（5）；
 * {@code documentId} 非空时按向量 metadata 的 documentId 过滤（可选检索过滤条件）。</p>
 */
@Data
public class RagQueryRequest {

    /** 用户问题（自然语言，必填）。 */
    @NotBlank(message = "query 不能为空")
    private String query;

    /** 返回条数 Top-K；为空取默认 5，上限受 max-top-k 约束。 */
    private Integer topK;

    /** 可选：按入库文档 documentId 过滤检索范围。 */
    private String documentId;
}

package com.zl.d32.document.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 相似度检索请求。
 *
 * <p>{@code topK} 为空时取配置默认值 5；{@code documentId} 非空时
 * 按向量 metadata 的 documentId 做过滤（该字段由 D31/D32 入库链路真实写入）。</p>
 */
@Data
public class VectorSearchRequest {

    /** 用户查询文本（自然语言问题）。 */
    @NotBlank(message = "query 不能为空")
    private String query;

    /** 返回条数 Top-K；为空取 app.ai.search.default-top-k。 */
    private Integer topK;

    /** 可选：按入库文档 documentId 过滤（metadata 过滤验证字段）。 */
    private String documentId;
}

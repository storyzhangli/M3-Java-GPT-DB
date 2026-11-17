package com.zl.d34.document.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zl.d34.document.model.KnowledgeDocument;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文档表 {@code knowledge_document} 的持久化对象。
 *
 * <p>PO 只负责与数据库行映射，不直接暴露给 Controller；与领域模型
 * {@link KnowledgeDocument} 之间由服务层转换。</p>
 */
@Data
@NoArgsConstructor
@TableName("knowledge_document")
public class KnowledgeDocumentPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    /**
     * 文档来源。
     *
     * <p>真机库沿用既有列名 {@code source_system}；不新增重复列，
     * 通过显式映射把领域字段 source 落到既有列，避免“代码能编译、真机导入失败”。</p>
     */
    @TableField("source_system")
    private String source;

    private String title;

    private Integer version;

    private String status;

    private String lifecycleStatus;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private String contentHash;

    /**
     * 原始字节长度。
     *
     * <p>真机库沿用 D26 既有列名为 {@code file_size}，与领域字段 contentLength 显式映射。</p>
     */
    @TableField("file_size")
    private Long contentLength;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

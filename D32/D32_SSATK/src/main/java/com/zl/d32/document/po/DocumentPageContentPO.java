package com.zl.d32.document.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档页面级提取结果表 {@code knowledge_document_page} 的持久化对象。
 *
 * <p>D32 真机库沿用 D27 既有列名，未再新增 source/title/version/created_at 列：
 * {@code character_count} 映射 contentLength、{@code blank_page} 映射 blank；
 * source/title/version 属于文档元数据，由页面服务在读取时从 knowledge_document 回填，
 * 不落页面表，避免同一元数据两处不一致。</p>
 *
 * <p>parser_name / parser_version 是 D27 既有非空列，导入时必须显式赋值，
 * 否则真机 INSERT 会因 NOT NULL 失败。</p>
 */
@Data
@NoArgsConstructor
@TableName("knowledge_document_page")
public class DocumentPageContentPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    /** 文档来源：不落页面表，读取时从文档元数据回填。 */
    @TableField(exist = false)
    private String source;

    /** 文档标题：不落页面表，读取时从文档元数据回填。 */
    @TableField(exist = false)
    private String title;

    /** 文档版本：不落页面表，读取时从文档元数据回填。 */
    @TableField(exist = false)
    private Integer version;

    private Integer pageNumber;

    private String content;

    /** 字符数，映射 D27 既有 character_count。 */
    @TableField("character_count")
    private Integer contentLength;

    /** 是否空白页，映射 D27 既有 blank_page。 */
    @TableField("blank_page")
    private Integer blank;

    private String parserName;

    private String parserVersion;

    /** 页面表无 created_at 列；保留领域字段但不参与持久化。 */
    @TableField(exist = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

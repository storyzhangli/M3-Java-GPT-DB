package com.zl.d32.document.vector.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 表 {@code vector_chunk_index} 的持久化对象。
 *
 * <p>该表保存 Chunk 内容快照与稳定 UUID，既是文档-Chunk 关联台账，
 * 也是失败恢复时的 Outbox 重放来源。</p>
 */
@Data
@NoArgsConstructor
@TableName("vector_chunk_index")
public class VectorChunkIndexPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    private String version;

    private String strategy;

    private String chunkId;

    private Integer chunkIndex;

    private String content;

    private String chunkContentHash;

    private Integer characterCount;

    private String pageNumbers;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

package com.zl.d32.document.vector.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 表 {@code vector_index_task} 的持久化对象。
 *
 * <p>该表由本地 D32 基线创建，唯一键 (document_id, version) 是并发幂等的数据库兜底；
 * version 沿用既有 varchar(64)，不强行改成整数，避免破坏已有表结构。</p>
 */
@Data
@NoArgsConstructor
@TableName("vector_index_task")
public class VectorIndexTaskPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    private String version;

    private String strategy;

    private String contentHash;

    private String splitVersion;

    private String vectorSpaceId;

    private Integer chunkCount;

    private String status;

    private String stage;

    private String errorCode;

    private String errorMessage;

    private Integer attemptCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

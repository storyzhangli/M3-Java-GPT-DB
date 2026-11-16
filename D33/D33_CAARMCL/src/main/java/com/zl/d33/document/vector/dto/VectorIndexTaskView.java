package com.zl.d33.document.vector.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 向量索引任务视图。duplicate=true 表示命中同版本已完成任务，未重复写入。
 */
@Data
public class VectorIndexTaskView {

    private String documentId;

    private String version;

    private String strategy;

    private String status;

    private String stage;

    private int chunkCount;

    private int indexedChunkCount;

    private int failedChunkCount;

    private int attemptCount;

    private String errorCode;

    private String errorMessage;

    private String splitVersion;

    private String vectorSpaceId;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private boolean duplicate;
}

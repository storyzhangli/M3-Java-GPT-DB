package com.zl.d34.document.vector.model;

import com.zl.d34.document.vector.model.VectorIndexStage;
import com.zl.d34.document.vector.model.VectorIndexStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 向量索引任务领域模型：一条记录对应“文档 + 版本”的一次向量化任务。
 */
@Data
public class VectorIndexTask {

    private Long id;

    private String documentId;

    /** 文档版本，字符串写入 MySQL，兼容既有 varchar(64) 列。 */
    private String version;

    private String strategy;

    /** 文档原始内容 SHA-256。 */
    private String contentHash;

    /** 切分参数指纹。 */
    private String splitVersion;

    /** Embedding 向量空间标识。 */
    private String vectorSpaceId;

    private int chunkCount;

    private VectorIndexStatus status;

    private VectorIndexStage stage;

    private String errorCode;

    private String errorMessage;

    private int attemptCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

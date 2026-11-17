package com.zl.d34.document.vector.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d34.document.vector.po.VectorIndexTaskPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 向量索引任务 Mapper。
 */
@Mapper
public interface VectorIndexTaskMapper extends BaseMapper<VectorIndexTaskPO> {

    /**
     * 数据库原生幂等 upsert：同一 (document_id, version) 并发提交只保留一行。
     *
     * <p>attempt_count 在冲突时自增，用于限制人工重试次数；状态字段以本次调用为准，
     * 由服务层在已完成场景提前返回，避免无意义重置。</p>
     */
    @Insert("""
            INSERT INTO vector_index_task
              (document_id, version, strategy, content_hash, split_version, vector_space_id,
               chunk_count, status, stage, error_code, error_message, attempt_count, next_retry_at,
               created_at, updated_at)
            VALUES
              (#{documentId}, #{version}, #{strategy}, #{contentHash}, #{splitVersion}, #{vectorSpaceId},
               #{chunkCount}, #{status}, #{stage}, #{errorCode}, #{errorMessage}, #{attemptCount}, #{nextRetryAt},
               #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
              strategy = VALUES(strategy),
              content_hash = VALUES(content_hash),
              split_version = VALUES(split_version),
              vector_space_id = VALUES(vector_space_id),
              chunk_count = VALUES(chunk_count),
              status = VALUES(status),
              stage = VALUES(stage),
              error_code = VALUES(error_code),
              error_message = VALUES(error_message),
              attempt_count = attempt_count + 1,
              next_retry_at = VALUES(next_retry_at),
              updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("documentId") String documentId,
               @Param("version") String version,
               @Param("strategy") String strategy,
               @Param("contentHash") String contentHash,
               @Param("splitVersion") String splitVersion,
               @Param("vectorSpaceId") String vectorSpaceId,
               @Param("chunkCount") int chunkCount,
               @Param("status") String status,
               @Param("stage") String stage,
               @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage,
               @Param("attemptCount") int attemptCount,
               @Param("nextRetryAt") java.time.LocalDateTime nextRetryAt,
               @Param("createdAt") java.time.LocalDateTime createdAt,
               @Param("updatedAt") java.time.LocalDateTime updatedAt);
}

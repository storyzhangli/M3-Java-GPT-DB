package com.zl.d34.document.vector.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d34.document.vector.po.VectorChunkIndexPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 向量 Chunk 台账 Mapper。
 */
@Mapper
public interface VectorChunkIndexMapper extends BaseMapper<VectorChunkIndexPO> {

    /**
     * 数据库原生幂等 upsert：uk_vector_chunk_chunk_id / uk_vector_chunk_doc_version_index
     * 在并发下兜底，禁止仅靠 Java 先查后插。
     */
    @Insert("""
            INSERT INTO vector_chunk_index
              (document_id, version, strategy, chunk_id, chunk_index, content, chunk_content_hash,
               character_count, page_numbers, status, created_at, updated_at)
            VALUES
              (#{documentId}, #{version}, #{strategy}, #{chunkId}, #{chunkIndex}, #{content}, #{chunkContentHash},
               #{characterCount}, #{pageNumbers}, #{status}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
              strategy = VALUES(strategy),
              chunk_index = VALUES(chunk_index),
              content = VALUES(content),
              chunk_content_hash = VALUES(chunk_content_hash),
              character_count = VALUES(character_count),
              page_numbers = VALUES(page_numbers),
              status = VALUES(status),
              updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("documentId") String documentId,
               @Param("version") String version,
               @Param("strategy") String strategy,
               @Param("chunkId") String chunkId,
               @Param("chunkIndex") int chunkIndex,
               @Param("content") String content,
               @Param("chunkContentHash") String chunkContentHash,
               @Param("characterCount") int characterCount,
               @Param("pageNumbers") String pageNumbers,
               @Param("status") String status,
               @Param("createdAt") java.time.LocalDateTime createdAt,
               @Param("updatedAt") java.time.LocalDateTime updatedAt);
}

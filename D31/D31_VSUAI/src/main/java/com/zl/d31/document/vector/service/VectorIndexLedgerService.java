package com.zl.d31.document.vector.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.zl.d31.document.vector.mapper.VectorChunkIndexMapper;
import com.zl.d31.document.vector.mapper.VectorIndexTaskMapper;
import com.zl.d31.document.vector.model.VectorChunkRecord;
import com.zl.d31.document.vector.model.VectorChunkStatus;
import com.zl.d31.document.vector.model.VectorIndexTask;
import com.zl.d31.document.vector.po.VectorChunkIndexPO;
import com.zl.d31.document.vector.po.VectorIndexTaskPO;
import com.zl.d31.document.vector.support.VectorIndexConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 向量索引台账服务：只负责 MySQL 本地事务内的任务/Chunk 状态与内容快照。
 *
 * <p>Embedding 调用与 PostgreSQL 写入不放进本类事务；跨系统一致性由
 * 上层状态机 + 重试/补偿 + 核对负责。</p>
 */
@Service
public class VectorIndexLedgerService {

    private final VectorIndexTaskMapper taskMapper;

    private final VectorChunkIndexMapper chunkMapper;

    private final VectorIndexConverter converter;

    public VectorIndexLedgerService(VectorIndexTaskMapper taskMapper,
                                    VectorChunkIndexMapper chunkMapper,
                                    VectorIndexConverter converter) {
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.converter = converter;
    }

    public VectorIndexTask findTask(String documentId, String version) {
        return converter.toDomain(taskMapper.selectOne(
                new QueryWrapper<VectorIndexTaskPO>()
                        .eq("document_id", documentId)
                        .eq("version", version)));
    }

    public List<VectorIndexTask> listTasks(String documentId) {
        return taskMapper.selectList(new QueryWrapper<VectorIndexTaskPO>()
                        .eq("document_id", documentId)
                        .orderByAsc("version"))
                .stream().map(converter::toDomain).toList();
    }

    public List<VectorChunkRecord> listChunks(String documentId, String version) {
        return chunkMapper.selectList(new QueryWrapper<VectorChunkIndexPO>()
                        .eq("document_id", documentId)
                        .eq("version", version)
                        .orderByAsc("chunk_index"))
                .stream().map(converter::toDomain).toList();
    }

    public List<VectorChunkRecord> listAllChunks(String documentId) {
        return chunkMapper.selectList(new QueryWrapper<VectorChunkIndexPO>()
                        .eq("document_id", documentId)
                        .orderByAsc("version").orderByAsc("chunk_index"))
                .stream().map(converter::toDomain).toList();
    }

    /**
     * 全局台账快照，用于跨文档孤儿检查。
     */
    public List<VectorChunkRecord> listAllChunksGlobal() {
        return chunkMapper.selectList(new QueryWrapper<VectorChunkIndexPO>()
                        .orderByAsc("document_id").orderByAsc("version").orderByAsc("chunk_index"))
                .stream().map(converter::toDomain).toList();
    }

    public int countChunks(String documentId, String version, VectorChunkStatus status) {
        QueryWrapper<VectorChunkIndexPO> wrapper = new QueryWrapper<VectorChunkIndexPO>()
                .eq("document_id", documentId)
                .eq("version", version);
        if (status != null) {
            wrapper.eq("status", status.name());
        }
        Long count = chunkMapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 数据库原生 upsert 任务；attempt_count 在冲突时加一。
     */
    @Transactional
    public VectorIndexTask prepareTask(VectorIndexTask task) {
        LocalDateTime now = LocalDateTime.now();
        taskMapper.upsert(
                task.getDocumentId(),
                task.getVersion(),
                task.getStrategy(),
                task.getContentHash(),
                task.getSplitVersion(),
                task.getVectorSpaceId(),
                task.getChunkCount(),
                task.getStatus().name(),
                task.getStage() == null ? null : task.getStage().name(),
                nullToEmpty(task.getErrorCode()),
                nullToEmpty(task.getErrorMessage()),
                1,
                task.getNextRetryAt(),
                task.getCreatedAt() == null ? now : task.getCreatedAt(),
                now);
        return findTask(task.getDocumentId(), task.getVersion());
    }

    /**
     * 幂等写入 Chunk 台账；不由 Java 先查后插。
     */
    @Transactional
    public void upsertChunks(List<VectorChunkRecord> chunks) {
        LocalDateTime now = LocalDateTime.now();
        for (VectorChunkRecord chunk : chunks) {
            chunkMapper.upsert(
                    chunk.getDocumentId(),
                    chunk.getVersion(),
                    chunk.getStrategy(),
                    chunk.getChunkId(),
                    chunk.getChunkIndex(),
                    chunk.getContent(),
                    chunk.getChunkContentHash(),
                    chunk.getCharacterCount(),
                    chunk.getPageNumbers() == null ? "" : chunk.getPageNumbers(),
                    chunk.getStatus().name(),
                    chunk.getCreatedAt() == null ? now : chunk.getCreatedAt(),
                    now);
        }
    }

    @Transactional
    public void updateChunkStatus(String chunkId, VectorChunkStatus status) {
        chunkMapper.update(null, new UpdateWrapper<VectorChunkIndexPO>()
                .eq("chunk_id", chunkId)
                .set("status", status.name())
                .set("updated_at", LocalDateTime.now()));
    }

    /**
     * 清理变更切分参数后不再出现在当前批次中的旧 Chunk 台账。
     */
    @Transactional
    public int deleteChunksByIds(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        return chunkMapper.delete(new QueryWrapper<VectorChunkIndexPO>()
                .in("chunk_id", chunkIds));
    }

    @Transactional
    public int deleteChunksByVersion(String documentId, String version) {
        return chunkMapper.delete(new QueryWrapper<VectorChunkIndexPO>()
                .eq("document_id", documentId)
                .eq("version", version));
    }

    @Transactional
    public int deleteAllChunks(String documentId) {
        return chunkMapper.delete(new QueryWrapper<VectorChunkIndexPO>()
                .eq("document_id", documentId));
    }

    @Transactional
    public int deleteAllTasks(String documentId) {
        return taskMapper.delete(new QueryWrapper<VectorIndexTaskPO>()
                .eq("document_id", documentId));
    }

    /**
     * 删除某文档的全部 Chunk 台账与任务台账（同事务，供文档删除编排调用）。
     */
    @Transactional
    public int[] deleteAllLedger(String documentId) {
        int chunks = chunkMapper.delete(new QueryWrapper<VectorChunkIndexPO>()
                .eq("document_id", documentId));
        int tasks = taskMapper.delete(new QueryWrapper<VectorIndexTaskPO>()
                .eq("document_id", documentId));
        return new int[]{chunks, tasks};
    }

    @Transactional
    public void updateTaskState(VectorIndexTask task) {
        taskMapper.update(null, new UpdateWrapper<VectorIndexTaskPO>()
                .eq("document_id", task.getDocumentId())
                .eq("version", task.getVersion())
                .set("status", task.getStatus().name())
                .set("stage", task.getStage() == null ? null : task.getStage().name())
                .set("error_code", nullToEmpty(task.getErrorCode()))
                .set("error_message", nullToEmpty(task.getErrorMessage()))
                .set("next_retry_at", task.getNextRetryAt())
                .set("updated_at", LocalDateTime.now()));
    }

    @Transactional
    public void markSuperseded(String documentId, String version) {
        VectorIndexTask task = findTask(documentId, version);
        if (task == null) {
            return;
        }
        taskMapper.update(null, new UpdateWrapper<VectorIndexTaskPO>()
                .eq("document_id", documentId)
                .eq("version", version)
                .set("status", "SUPERSEDED")
                .set("stage", "CLEANUP")
                .set("updated_at", LocalDateTime.now()));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

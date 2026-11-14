package com.zl.d31.document.vector.service;

import com.zl.d31.document.chunk.config.ChunkProperties;
import com.zl.d31.document.chunk.model.Chunk;
import com.zl.d31.document.chunk.model.ChunkStrategy;
import com.zl.d31.document.chunk.service.DocumentChunkService;
import com.zl.d31.document.embedding.config.EmbeddingProperties;
import com.zl.d31.document.embedding.model.EmbeddingRecord;
import com.zl.d31.document.embedding.service.DocumentEmbeddingService;
import com.zl.d31.document.exception.DocumentValidationException;
import com.zl.d31.document.model.DocumentPageContent;
import com.zl.d31.document.model.DocumentStatus;
import com.zl.d31.document.model.KnowledgeDocument;
import com.zl.d31.document.service.DocumentPageService;
import com.zl.d31.document.service.DocumentRegistrationService;
import com.zl.d31.document.support.StableIdGenerator;
import com.zl.d31.document.vector.config.VectorIndexProperties;
import com.zl.d31.document.vector.dto.VectorDeleteView;
import com.zl.d31.document.vector.dto.VectorIndexTaskView;
import com.zl.d31.document.vector.model.VectorChunkRecord;
import com.zl.d31.document.vector.model.VectorChunkStatus;
import com.zl.d31.document.vector.model.VectorIndexStage;
import com.zl.d31.document.vector.model.VectorIndexStatus;
import com.zl.d31.document.vector.model.VectorIndexTask;
import com.zl.d31.document.vector.repository.VectorStoreJdbcRepository;
import com.zl.d31.document.vector.support.VectorIndexConverter;
import com.zl.d31.document.vector.support.VectorStoreRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * D31 文档向量索引编排服务。
 *
 * <p>核心边界：</p>
 * <ul>
 *   <li>MySQL 台账事务与 PostgreSQL 写入不在同一事务；Embedding 是外部调用；</li>
 *   <li>写入 PostgreSQL 前先落 Chunk Outbox 快照，失败可通过稳定 ID 重试；</li>
 *   <li>旧版本向量在新版本 COMPLETED 前保留，成功后统一清理，避免更新中断导致全量丢失；</li>
 *   <li>每次写入使用稳定 UUID v5 + PostgreSQL ON CONFLICT upsert，重复执行不产生新行。</li>
 * </ul>
 */
@Slf4j
@Service
public class DocumentVectorIndexService {

    private final DocumentRegistrationService documentService;

    private final DocumentPageService pageService;

    private final DocumentChunkService chunkService;

    private final DocumentEmbeddingService embeddingService;

    private final StableIdGenerator idGenerator;

    private final VectorIndexLedgerService ledger;

    private final VectorStoreJdbcRepository vectorRepository;

    private final VectorIndexConverter converter;

    private final EmbeddingProperties embeddingProperties;

    private final ChunkProperties chunkProperties;

    private final VectorIndexProperties indexProperties;

    public DocumentVectorIndexService(DocumentRegistrationService documentService,
                                      DocumentPageService pageService,
                                      DocumentChunkService chunkService,
                                      DocumentEmbeddingService embeddingService,
                                      StableIdGenerator idGenerator,
                                      VectorIndexLedgerService ledger,
                                      VectorStoreJdbcRepository vectorRepository,
                                      VectorIndexConverter converter,
                                      EmbeddingProperties embeddingProperties,
                                      ChunkProperties chunkProperties,
                                      VectorIndexProperties indexProperties) {
        this.documentService = documentService;
        this.pageService = pageService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.idGenerator = idGenerator;
        this.ledger = ledger;
        this.vectorRepository = vectorRepository;
        this.converter = converter;
        this.embeddingProperties = embeddingProperties;
        this.chunkProperties = chunkProperties;
        this.indexProperties = indexProperties;
    }

    /**
     * 新增/确保文档当前版本的向量索引。
     *
     * <p>同一个 documentId + version + contentHash + splitVersion 已完成且数据库向量齐全时，
     * 直接返回 duplicate=true，不重复调用 Embedding、不重复写 PG。</p>
     */
    public VectorIndexTaskView indexDocument(String documentId, ChunkStrategy strategy) {
        KnowledgeDocument doc = loadIndexableDocument(documentId);
        if (strategy == null) {
            strategy = ChunkStrategy.FIXED_SIZE;
        }
        List<Chunk> chunks = split(doc, strategy);
        List<VectorChunkRecord> records = buildRecords(doc, chunks, strategy);
        String contentHash = requireContentHash(doc);

        VectorIndexTask existing = ledger.findTask(documentId, String.valueOf(doc.getVersion()));
        if (existing != null && existing.getStatus() == VectorIndexStatus.COMPLETED
                && sameBatch(existing, records, contentHash)) {
            Set<String> expectedIds = new HashSet<>(chunkIds(records));
            Set<String> existingIds = new HashSet<>(chunkIds(
                    ledger.listChunks(documentId, existing.getVersion())));
            List<String> missing = vectorRepository.findMissingIds(chunkIds(records));
            int indexed = ledger.countChunks(documentId, existing.getVersion(), VectorChunkStatus.INDEXED);
            if (expectedIds.equals(existingIds) && missing.isEmpty() && indexed == records.size()) {
                VectorIndexTaskView view = buildView(existing, indexed, 0, true);
                log.info("向量索引已存在且核对一致，跳过重复写入：documentId={}, version={}, chunks={}",
                        documentId, existing.getVersion(), records.size());
                return view;
            }
        }
        return execute(doc, strategy, records, contentHash, existing);
    }

    /**
     * 人工重试：只对当前文档版本，复用稳定 ID；已 INDEXED 的 Chunk 不重复嵌入。
     */
    public VectorIndexTaskView retry(String documentId, String version) {
        KnowledgeDocument doc = documentService.getByDocumentId(documentId);
        String currentVersion = String.valueOf(doc.getVersion());
        if (version == null || !version.equals(currentVersion)) {
            throw new DocumentValidationException(
                    "只能重试文档当前版本，当前=" + currentVersion + "，请求=" + version);
        }
        VectorIndexTask existing = ledger.findTask(documentId, currentVersion);
        if (existing == null) {
            throw new DocumentValidationException("向量索引任务不存在: " + documentId + "/" + version);
        }
        if (existing.getStatus() == VectorIndexStatus.SUPERSEDED) {
            throw new DocumentValidationException("已过期版本不能重试: " + version);
        }
        if (existing.getStatus() == VectorIndexStatus.COMPLETED) {
            List<VectorChunkRecord> existingChunks = ledger.listChunks(documentId, currentVersion);
            List<String> missing = vectorRepository.findMissingIds(chunkIds(existingChunks));
            if (missing.isEmpty()) {
                return buildView(existing,
                        ledger.countChunks(documentId, currentVersion, VectorChunkStatus.INDEXED),
                        0, true);
            }
        }
        if (existing.getAttemptCount() >= indexProperties.getMaxTaskAttempts()) {
            markExhausted(existing);
            return buildView(existing, countIndexed(existing), 0, false);
        }
        ChunkStrategy strategy = parseStrategy(existing.getStrategy());
        List<Chunk> chunks = split(doc, strategy);
        List<VectorChunkRecord> records = buildRecords(doc, chunks, strategy);
        return execute(doc, strategy, records, requireContentHash(doc), existing);
    }

    public VectorIndexTaskView currentTask(String documentId) {
        KnowledgeDocument doc = documentService.getByDocumentId(documentId);
        VectorIndexTask task = ledger.findTask(documentId, String.valueOf(doc.getVersion()));
        if (task == null) {
            return null;
        }
        return buildView(task,
                ledger.countChunks(documentId, task.getVersion(), VectorChunkStatus.INDEXED),
                0, false);
    }

    public List<VectorChunkRecord> listChunks(String documentId, String version) {
        return ledger.listChunks(documentId, version);
    }

    /**
     * 仅清理向量和台账，不删除文档元数据/页面；供文档删除编排先执行。
     */
    public VectorDeleteView deleteVectorsOnly(String documentId) {
        List<VectorChunkRecord> chunks = ledger.listAllChunks(documentId);
        List<String> ids = chunkIds(chunks);
        int deletedVectors = vectorRepository.deleteManagedByDocument(documentId);
        if (!ids.isEmpty()) {
            // 兼容 metadata 缺失的历史行：按稳定 chunkId 再删一次，避免孤儿。
            deletedVectors += vectorRepository.deleteByIds(ids);
        }
        if (vectorRepository.countManagedByDocument(documentId) > 0
                || (!ids.isEmpty() && vectorRepository.countByIds(ids) > 0)) {
            throw new IllegalStateException("向量删除后仍存在残留，已中止文档删除，等待重试");
        }
        int[] ledgerRows = ledger.deleteAllLedger(documentId);
        VectorDeleteView view = new VectorDeleteView();
        view.setDocumentId(documentId);
        view.setDeletedChunkRows(ledgerRows[0]);
        view.setDeletedTaskRows(ledgerRows[1]);
        view.setDeletedVectorRows(deletedVectors);
        view.setStatus("PASS");
        log.info("文档向量清理完成：documentId={}，chunkRows={}，taskRows={}，vectorRows={}",
                documentId, ledgerRows[0], ledgerRows[1], deletedVectors);
        return view;
    }

    private VectorIndexTaskView execute(KnowledgeDocument doc, ChunkStrategy strategy,
                                        List<VectorChunkRecord> records, String contentHash,
                                        VectorIndexTask existing) {
        String version = String.valueOf(doc.getVersion());
        String splitVersion = idGenerator.splitVersion(strategy, chunkProperties);
        String vectorSpaceId = embeddingProperties.vectorSpaceId();

        if (existing != null && existing.getStatus() != VectorIndexStatus.COMPLETED
                && existing.getAttemptCount() >= indexProperties.getMaxTaskAttempts()) {
            markExhausted(existing);
            return buildView(existing, countIndexed(existing), 0, false);
        }

        VectorIndexTask task = new VectorIndexTask();
        task.setDocumentId(doc.getDocumentId());
        task.setVersion(version);
        task.setStrategy(strategy.name());
        task.setContentHash(contentHash);
        task.setSplitVersion(splitVersion);
        task.setVectorSpaceId(vectorSpaceId);
        task.setChunkCount(records.size());
        task.setStatus(VectorIndexStatus.INDEXING);
        task.setStage(VectorIndexStage.REGISTER);
        task.setErrorCode("");
        task.setErrorMessage("");
        task.setAttemptCount(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        try {
            task = ledger.prepareTask(task);
            if (task == null) {
                throw new IllegalStateException("向量任务 upsert 后未读取到任务");
            }
        } catch (RuntimeException e) {
            log.error("向量任务登记失败：documentId={}, version={}, type={}",
                    doc.getDocumentId(), version, e.getClass().getSimpleName());
            throw e;
        }

        List<VectorChunkRecord> existingChunks = ledger.listChunks(doc.getDocumentId(), version);
        Map<String, VectorChunkRecord> existingById = new HashMap<>();
        for (VectorChunkRecord chunk : existingChunks) {
            existingById.put(chunk.getChunkId(), chunk);
        }

        // 同版本切分参数变化后，旧集合中的 Chunk 与向量必须先清理，避免残留。
        List<String> staleIds = existingById.keySet().stream()
                .filter(id -> records.stream().noneMatch(r -> r.getChunkId().equals(id)))
                .toList();
        if (!staleIds.isEmpty()) {
            try {
                vectorRepository.deleteByIds(staleIds);
                ledger.deleteChunksByIds(staleIds);
                log.info("清理同版本旧切分 Chunk：documentId={}, version={}, count={}",
                        doc.getDocumentId(), version, staleIds.size());
            } catch (RuntimeException e) {
                markTaskFailed(task, VectorIndexStage.CLEANUP, "STALE_CHUNK_CLEANUP_FAILED", e);
                return buildView(task, 0, records.size(), false);
            }
        }

        List<String> expectedIds = chunkIds(records);
        List<String> indexedExistingIds = existingById.values().stream()
                .filter(c -> c.getStatus() == VectorChunkStatus.INDEXED)
                .map(VectorChunkRecord::getChunkId)
                .toList();
        List<String> missingVectors = vectorRepository.findMissingIds(indexedExistingIds);
        // 台账显示 INDEXED 但向量丢失：重置为 PENDING，进入本次补偿写入。
        for (String missingId : missingVectors) {
            ledger.updateChunkStatus(missingId, VectorChunkStatus.PENDING);
            VectorChunkRecord record = existingById.get(missingId);
            if (record != null) {
                record.setStatus(VectorChunkStatus.PENDING);
            }
        }

        List<VectorChunkRecord> toProcess = new ArrayList<>();
        for (VectorChunkRecord record : records) {
            VectorChunkRecord old = existingById.get(record.getChunkId());
            boolean alreadyIndexed = old != null
                    && old.getStatus() == VectorChunkStatus.INDEXED
                    && Objects.equals(old.getChunkContentHash(), record.getChunkContentHash())
                    && !missingVectors.contains(record.getChunkId());
            if (!alreadyIndexed) {
                record.setStatus(VectorChunkStatus.PENDING);
                toProcess.add(record);
            }
        }
        if (!toProcess.isEmpty()) {
            ledger.upsertChunks(toProcess);
        }

        Map<Integer, Chunk> chunksByIndex = new HashMap<>();
        for (Chunk chunk : split(doc, strategy)) {
            chunksByIndex.put(chunk.getChunkIndex(), chunk);
        }

        List<String> failures = new ArrayList<>();
        for (VectorChunkRecord record : toProcess) {
            Chunk chunk = chunksByIndex.get(record.getChunkIndex());
            if (chunk == null) {
                ledger.updateChunkStatus(record.getChunkId(), VectorChunkStatus.FAILED);
                failures.add(record.getChunkId());
                continue;
            }
            try {
                EmbeddingRecord embedding = embeddingService.embedChunk(chunk);
                vectorRepository.upsert(toVectorRecord(doc, record, embedding));
                ledger.updateChunkStatus(record.getChunkId(), VectorChunkStatus.INDEXED);
            } catch (RuntimeException e) {
                ledger.updateChunkStatus(record.getChunkId(), VectorChunkStatus.FAILED);
                failures.add(record.getChunkId());
                log.warn("Chunk 向量写入失败：documentId={}, version={}, chunkIndex={}, type={}, message={}",
                        doc.getDocumentId(), version, record.getChunkIndex(),
                        e.getClass().getSimpleName(), safeMessage(e));
            }
        }

        int indexed = ledger.countChunks(doc.getDocumentId(), version, VectorChunkStatus.INDEXED);
        List<String> stillMissing = vectorRepository.findMissingIds(expectedIds);
        boolean complete = failures.isEmpty() && stillMissing.isEmpty() && indexed == records.size();

        if (complete) {
            task.setStatus(VectorIndexStatus.COMPLETED);
            task.setStage(VectorIndexStage.VERIFY);
            task.setErrorCode("");
            task.setErrorMessage("");
            task.setNextRetryAt(null);
            ledger.updateTaskState(task);
            try {
                if (indexProperties.isCleanupOldVersions()) {
                    cleanupOldVersions(doc.getDocumentId(), version);
                }
                log.info("向量索引完成：documentId={}, version={}, chunks={}",
                        doc.getDocumentId(), version, records.size());
                return buildView(task, indexed, 0, false);
            } catch (RuntimeException e) {
                // 新版本向量已完整，但旧版本清理失败：标记 PARTIAL/CLEANUP，重试只重做清理与核对。
                task.setStatus(VectorIndexStatus.PARTIAL);
                task.setStage(VectorIndexStage.CLEANUP);
                task.setErrorCode("OLD_VERSION_CLEANUP_FAILED");
                task.setErrorMessage(safeMessage(e));
                task.setNextRetryAt(nextRetry());
                ledger.updateTaskState(task);
                log.warn("新版本向量完成但旧版本清理失败：documentId={}, version={}, type={}",
                        doc.getDocumentId(), version, e.getClass().getSimpleName());
                return buildView(task, indexed, 0, false);
            }
        }

        int failed = Math.max(0, records.size() - indexed);
        boolean exhausted = task.getAttemptCount() >= indexProperties.getMaxTaskAttempts();
        task.setStatus(exhausted ? VectorIndexStatus.FAILED
                : (indexed == 0 ? VectorIndexStatus.FAILED : VectorIndexStatus.PARTIAL));
        task.setStage(VectorIndexStage.EMBED_WRITE);
        task.setErrorCode(exhausted ? "MAX_ATTEMPTS_EXHAUSTED"
                : (stillMissing.isEmpty() ? "PARTIAL_EMBED_WRITE" : "VECTOR_COUNT_MISMATCH"));
        task.setErrorMessage(failures.isEmpty()
                ? "向量写入后数据库计数不一致，可能存在缺失向量"
                : "部分 Chunk 向量写入失败，失败数=" + failures.size());
        task.setNextRetryAt(exhausted ? null : nextRetry());
        ledger.updateTaskState(task);
        log.warn("向量索引未完成：documentId={}, version={}, indexed={}, total={}, status={}, stage={}",
                doc.getDocumentId(), version, indexed, records.size(), task.getStatus(), task.getStage());
        return buildView(task, indexed, failed, false);
    }

    private void cleanupOldVersions(String documentId, String currentVersion) {
        List<VectorIndexTask> tasks = ledger.listTasks(documentId);
        for (VectorIndexTask old : tasks) {
            if (currentVersion.equals(old.getVersion())
                    || old.getStatus() == VectorIndexStatus.SUPERSEDED) {
                continue;
            }
            List<VectorChunkRecord> oldChunks = ledger.listChunks(documentId, old.getVersion());
            if (!oldChunks.isEmpty()) {
                vectorRepository.deleteByIds(chunkIds(oldChunks));
            }
            ledger.deleteChunksByVersion(documentId, old.getVersion());
            ledger.markSuperseded(documentId, old.getVersion());
            log.info("旧版本向量已清理：documentId={}, version={}, chunks={}",
                    documentId, old.getVersion(), oldChunks.size());
        }
    }

    private VectorStoreRecord toVectorRecord(KnowledgeDocument doc, VectorChunkRecord chunk,
                                             EmbeddingRecord embedding) {
        VectorStoreRecord record = new VectorStoreRecord();
        record.setId(chunk.getChunkId());
        record.setContent(chunk.getContent());
        record.setEmbedding(embedding.getVector());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("managedBy", indexProperties.getManagedBy());
        metadata.put("documentId", doc.getDocumentId());
        metadata.put("version", String.valueOf(doc.getVersion()));
        metadata.put("strategy", chunk.getStrategy());
        metadata.put("chunkId", chunk.getChunkId());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("contentHash", chunk.getChunkContentHash());
        metadata.put("vectorSpaceId", embedding.getVectorSpaceId());
        metadata.put("modelName", embedding.getModelName());
        metadata.put("dimensions", embedding.getDimensions());
        metadata.put("source", doc.getSource());
        metadata.put("title", doc.getTitle());
        metadata.put("pageNumbers", chunk.getPageNumbers());
        record.setMetadata(metadata);
        return record;
    }

    private List<VectorChunkRecord> buildRecords(KnowledgeDocument doc, List<Chunk> chunks,
                                                 ChunkStrategy strategy) {
        String version = String.valueOf(doc.getVersion());
        String splitVersion = idGenerator.splitVersion(strategy, chunkProperties);
        List<VectorChunkRecord> records = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            String chunkHash = idGenerator.contentHash(chunk.getContent());
            VectorChunkRecord record = new VectorChunkRecord();
            record.setDocumentId(doc.getDocumentId());
            record.setVersion(version);
            record.setStrategy(strategy.name());
            record.setChunkId(idGenerator.chunkId(
                    doc.getDocumentId(), version, strategy, splitVersion,
                    chunk.getChunkIndex(), chunkHash));
            record.setChunkIndex(chunk.getChunkIndex());
            record.setContent(chunk.getContent());
            record.setChunkContentHash(chunkHash);
            record.setCharacterCount(chunk.getLength());
            record.setPageNumbers(String.join(",", chunk.getPageNumbers().stream()
                    .map(String::valueOf).toList()));
            record.setStatus(VectorChunkStatus.PENDING);
            record.setCreatedAt(LocalDateTime.now());
            records.add(record);
        }
        return records;
    }

    private List<Chunk> split(KnowledgeDocument doc, ChunkStrategy strategy) {
        List<DocumentPageContent> pages = pageService.listPages(doc.getDocumentId());
        if (pages.isEmpty()) {
            throw new DocumentValidationException(
                    "文档没有已持久化页面，无法切分/向量化: " + doc.getDocumentId());
        }
        for (DocumentPageContent page : pages) {
            page.setSource(doc.getSource());
            page.setTitle(doc.getTitle());
            page.setVersion(doc.getVersion());
        }
        List<Chunk> chunks = chunkService.runStrategy(pages, strategy);
        if (chunks.isEmpty()) {
            throw new DocumentValidationException("文档切分结果为空: " + doc.getDocumentId());
        }
        return chunks;
    }

    private KnowledgeDocument loadIndexableDocument(String documentId) {
        KnowledgeDocument doc = documentService.getByDocumentId(documentId);
        if (doc.getStatus() != DocumentStatus.COMPLETED) {
            throw new DocumentValidationException(
                    "仅允许对 COMPLETED 文档建立向量索引，当前=" + doc.getStatus());
        }
        return doc;
    }

    private String requireContentHash(KnowledgeDocument doc) {
        if (doc.getContentHash() == null || doc.getContentHash().isBlank()) {
            throw new DocumentValidationException("文档缺少内容指纹，无法建立向量索引: " + doc.getDocumentId());
        }
        return doc.getContentHash();
    }

    private ChunkStrategy parseStrategy(String value) {
        try {
            return ChunkStrategy.valueOf(value);
        } catch (RuntimeException e) {
            throw new DocumentValidationException("未知切分策略: " + value);
        }
    }

    private boolean sameBatch(VectorIndexTask task, List<VectorChunkRecord> records, String contentHash) {
        return Objects.equals(task.getContentHash(), contentHash)
                && Objects.equals(task.getSplitVersion(),
                        idGenerator.splitVersion(parseStrategy(task.getStrategy()), chunkProperties))
                && task.getChunkCount() == records.size();
    }

    private List<String> chunkIds(Collection<VectorChunkRecord> records) {
        return records.stream().map(VectorChunkRecord::getChunkId).toList();
    }

    private int countIndexed(VectorIndexTask task) {
        return ledger.countChunks(task.getDocumentId(), task.getVersion(), VectorChunkStatus.INDEXED);
    }

    private void markExhausted(VectorIndexTask task) {
        task.setStatus(VectorIndexStatus.FAILED);
        task.setStage(VectorIndexStage.EMBED_WRITE);
        task.setErrorCode("MAX_ATTEMPTS_EXHAUSTED");
        task.setErrorMessage("向量索引重试次数已耗尽，需人工介入");
        task.setNextRetryAt(null);
        ledger.updateTaskState(task);
        log.warn("向量索引重试次数耗尽：documentId={}, version={}, attempts={}",
                task.getDocumentId(), task.getVersion(), task.getAttemptCount());
    }

    private void markTaskFailed(VectorIndexTask task, VectorIndexStage stage,
                               String errorCode, RuntimeException e) {
        task.setStatus(VectorIndexStatus.FAILED);
        task.setStage(stage);
        task.setErrorCode(errorCode);
        task.setErrorMessage(safeMessage(e));
        task.setNextRetryAt(nextRetry());
        ledger.updateTaskState(task);
    }

    private LocalDateTime nextRetry() {
        return LocalDateTime.now().plusSeconds(indexProperties.getRetryDelaySeconds());
    }

    private String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "...";
    }

    private VectorIndexTaskView buildView(VectorIndexTask task, int indexed, int failed,
                                         boolean duplicate) {
        VectorIndexTaskView view = converter.toView(task);
        view.setIndexedChunkCount(indexed);
        view.setFailedChunkCount(failed);
        view.setDuplicate(duplicate);
        return view;
    }
}

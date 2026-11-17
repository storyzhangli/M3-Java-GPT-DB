package com.zl.d34.document.vector.service;

import com.zl.d34.document.chunk.model.ChunkStrategy;
import com.zl.d34.document.model.KnowledgeDocument;
import com.zl.d34.document.service.DocumentRegistrationService;
import com.zl.d34.document.vector.dto.VectorIndexConsistencyView;
import com.zl.d34.document.vector.dto.VectorIndexTaskView;
import com.zl.d34.document.vector.model.VectorChunkRecord;
import com.zl.d34.document.vector.model.VectorChunkStatus;
import com.zl.d34.document.vector.model.VectorIndexStatus;
import com.zl.d34.document.vector.model.VectorIndexTask;
import com.zl.d34.document.vector.repository.VectorStoreJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 原文库（MySQL 台账）与向量库（PostgreSQL/pgvector）的一致性核对与补偿。
 *
 * <p>核对口径：</p>
 * <ul>
 *   <li>台账 INDEXED Chunk 必须在 PG 中存在同 UUID 行，否则 missing；</li>
 *   <li>PG 中 managedBy=d34 的向量若不属于该文档任何版本台账，则为 orphan；</li>
 *   <li>文档 version 与最新任务 version 不一致，标记 stale；</li>
 *   <li>只有 COMPLETED 且无 missing/orphan 才 PASS；其余 FAIL，供补偿入口处理。</li>
 * </ul>
 */
@Service
public class VectorIndexConsistencyService {

    private final DocumentRegistrationService documentService;

    private final VectorIndexLedgerService ledger;

    private final DocumentVectorIndexService vectorIndexService;

    private final VectorStoreJdbcRepository vectorRepository;

    public VectorIndexConsistencyService(DocumentRegistrationService documentService,
                                         VectorIndexLedgerService ledger,
                                         DocumentVectorIndexService vectorIndexService,
                                         VectorStoreJdbcRepository vectorRepository) {
        this.documentService = documentService;
        this.ledger = ledger;
        this.vectorIndexService = vectorIndexService;
        this.vectorRepository = vectorRepository;
    }

    public VectorIndexConsistencyView checkDocument(String documentId) {
        KnowledgeDocument doc = documentService.getByDocumentId(documentId);
        String version = String.valueOf(doc.getVersion());
        VectorIndexTask task = ledger.findTask(documentId, version);
        List<VectorChunkRecord> chunks = ledger.listChunks(documentId, version);
        List<VectorChunkRecord> allChunks = ledger.listAllChunks(documentId);
        List<String> managedIds = vectorRepository.listManagedIdsByDocument(documentId);

        Set<String> managedIdSet = new HashSet<>(managedIds);
        List<String> missing = new ArrayList<>();
        for (VectorChunkRecord chunk : chunks) {
            if (chunk.getStatus() == VectorChunkStatus.INDEXED
                    && !managedIdSet.contains(chunk.getChunkId())) {
                missing.add(chunk.getChunkId());
            }
        }

        Set<String> ledgerIds = new HashSet<>();
        for (VectorChunkRecord chunk : allChunks) {
            ledgerIds.add(chunk.getChunkId());
        }
        List<String> orphan = managedIds.stream()
                .filter(id -> !ledgerIds.contains(id))
                .toList();

        VectorIndexConsistencyView view = new VectorIndexConsistencyView();
        view.setDocumentId(documentId);
        view.setDocumentVersion(doc.getVersion());
        view.setTaskVersion(task == null ? null : task.getVersion());
        view.setTaskStatus(task == null ? null : task.getStatus().name());
        view.setChunkCount(chunks.size());
        view.setIndexedChunkCount((int) chunks.stream()
                .filter(c -> c.getStatus() == VectorChunkStatus.INDEXED).count());
        view.setVectorCount(managedIds.size());
        view.setMissingVectorCount(missing.size());
        view.setOrphanVectorCount(orphan.size());
        view.setStaleDocumentVersion(task == null || !version.equals(task.getVersion()));
        view.setMissingChunkIds(limit(missing));
        view.setOrphanVectorIds(limit(orphan));

        List<String> findings = view.getFindings();
        if (task == null) {
            findings.add("TASK_MISSING");
        } else if (task.getStatus() != VectorIndexStatus.COMPLETED) {
            findings.add("TASK_NOT_COMPLETED");
        }
        if (!missing.isEmpty()) {
            findings.add("MISSING_VECTOR");
        }
        if (!orphan.isEmpty()) {
            findings.add("ORPHAN_VECTOR");
        }
        if (view.getIndexedChunkCount() < chunks.size()) {
            // 任务可能被人为改成 COMPLETED，但台账仍有 FAILED/PENDING Chunk；不能只看任务状态。
            findings.add("CHUNK_NOT_INDEXED");
        }
        if (view.getVectorCount() < chunks.size()) {
            findings.add("VECTOR_COUNT_MISMATCH");
        }
        if (view.isStaleDocumentVersion()) {
            findings.add("STALE_DOCUMENT_VERSION");
        }
        view.setStatus(findings.isEmpty() ? "PASS" : "FAIL");
        return view;
    }

    /**
     * 全局核对：不修改数据，只识别 orphan 与缺失向量。
     */
    public VectorIndexConsistencyView checkGlobal() {
        List<VectorChunkRecord> allChunks = ledger.listAllChunksGlobal();
        List<String> managedIds = vectorRepository.listManagedIds();
        Set<String> managedIdSet = new HashSet<>(managedIds);
        Set<String> ledgerIds = new HashSet<>();

        List<String> missing = new ArrayList<>();
        for (VectorChunkRecord chunk : allChunks) {
            ledgerIds.add(chunk.getChunkId());
            if (chunk.getStatus() == VectorChunkStatus.INDEXED
                    && !managedIdSet.contains(chunk.getChunkId())) {
                missing.add(chunk.getChunkId());
            }
        }
        List<String> orphan = managedIds.stream()
                .filter(id -> !ledgerIds.contains(id))
                .toList();

        VectorIndexConsistencyView view = new VectorIndexConsistencyView();
        view.setDocumentId(null);
        view.setDocumentVersion(0);
        view.setChunkCount(allChunks.size());
        view.setIndexedChunkCount((int) allChunks.stream()
                .filter(c -> c.getStatus() == VectorChunkStatus.INDEXED).count());
        view.setVectorCount(managedIds.size());
        view.setMissingVectorCount(missing.size());
        view.setOrphanVectorCount(orphan.size());
        view.setMissingChunkIds(limit(missing));
        view.setOrphanVectorIds(limit(orphan));
        if (!missing.isEmpty()) {
            view.getFindings().add("MISSING_VECTOR");
        }
        if (!orphan.isEmpty()) {
            view.getFindings().add("ORPHAN_VECTOR");
        }
        view.setStatus(view.getFindings().isEmpty() ? "PASS" : "FAIL");
        return view;
    }

    /**
     * 文档级补偿：先删孤儿，再按当前任务重试缺失 Chunk；不无条件全量重建。
     */
    public VectorIndexTaskView repair(String documentId) {
        List<String> allOrphans = collectOrphanIds(documentId);
        if (!allOrphans.isEmpty()) {
            vectorRepository.deleteByIds(allOrphans);
        }

        KnowledgeDocument doc = documentService.getByDocumentId(documentId);
        String version = String.valueOf(doc.getVersion());
        VectorIndexTask task = ledger.findTask(documentId, version);
        if (task == null) {
            return vectorIndexService.indexDocument(documentId, ChunkStrategy.FIXED_SIZE);
        }
        return vectorIndexService.retry(documentId, version);
    }

    /**
     * 全局孤儿补偿：只删除 PG 中 managedBy=d34 且不属于任何台账的向量。
     */
    public int repairGlobalOrphans() {
        List<String> orphans = collectGlobalOrphanIds();
        if (orphans.isEmpty()) {
            return 0;
        }
        return vectorRepository.deleteByIds(orphans);
    }

    private List<String> collectOrphanIds(String documentId) {
        Set<String> ledgerIds = new HashSet<>();
        for (VectorChunkRecord chunk : ledger.listAllChunks(documentId)) {
            ledgerIds.add(chunk.getChunkId());
        }
        return vectorRepository.listManagedIdsByDocument(documentId).stream()
                .filter(id -> !ledgerIds.contains(id))
                .toList();
    }

    private List<String> collectGlobalOrphanIds() {
        Set<String> ledgerIds = new HashSet<>();
        for (VectorChunkRecord chunk : ledger.listAllChunksGlobal()) {
            ledgerIds.add(chunk.getChunkId());
        }
        return vectorRepository.listManagedIds().stream()
                .filter(id -> !ledgerIds.contains(id))
                .toList();
    }

    private List<String> limit(List<String> ids) {
        return ids.size() <= 20 ? ids : ids.subList(0, 20);
    }
}

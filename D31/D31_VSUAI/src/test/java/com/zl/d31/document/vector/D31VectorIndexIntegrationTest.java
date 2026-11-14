package com.zl.d31.document.vector;

import com.zl.d31.document.chunk.model.ChunkStrategy;
import com.zl.d31.document.dto.DocumentImportView;
import com.zl.d31.document.exception.DocumentValidationException;
import com.zl.d31.document.model.KnowledgeDocument;
import com.zl.d31.document.service.DocumentImportService;
import com.zl.d31.document.service.DocumentRegistrationService;
import com.zl.d31.document.vector.dto.VectorDeleteView;
import com.zl.d31.document.vector.dto.VectorIndexConsistencyView;
import com.zl.d31.document.vector.dto.VectorIndexTaskView;
import com.zl.d31.document.vector.model.VectorChunkRecord;
import com.zl.d31.document.vector.model.VectorChunkStatus;
import com.zl.d31.document.vector.model.VectorIndexStatus;
import com.zl.d31.document.vector.model.VectorIndexTask;
import com.zl.d31.document.vector.repository.VectorStoreJdbcRepository;
import com.zl.d31.document.vector.service.DocumentDeletionService;
import com.zl.d31.document.vector.service.DocumentVectorIndexService;
import com.zl.d31.document.vector.service.VectorIndexConsistencyService;
import com.zl.d31.document.vector.service.VectorIndexLedgerService;
import com.zl.d31.document.vector.support.VectorStoreRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * D31 向量入库、更新与幂等真机集成测试（显式门控，默认不执行）。
 *
 * <p>门控：环境变量 {@code D31_VECTOR_IT=true}；真实连接 MySQL ai_db 与
 * PostgreSQL spring_ai，不使用 Mock 数据库结果冒充持久化。</p>
 *
 * <p>覆盖：新增、重复导入/重复索引、更新切换、删除、失败重试、部分失败恢复、
 * 台账/PG 一致性核对与补偿。测试数据使用唯一 source/title，结束后删除。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.vectorstore.pgvector.initialize-schema=true",
                "spring.ai.vectorstore.pgvector.schema-validation=false",
                "app.ai.embedding.max-attempts=2"
        })
@EnabledIfEnvironmentVariable(named = "D31_VECTOR_IT", matches = "true")
class D31VectorIndexIntegrationTest {

    private static final String MARKER = "INJECTED_EMBED_FAILURE_MARKER";

    @Autowired
    private DocumentImportService importService;

    @Autowired
    private DocumentRegistrationService documentService;

    @Autowired
    private DocumentVectorIndexService vectorIndexService;

    @Autowired
    private VectorIndexLedgerService ledger;

    @Autowired
    private VectorIndexConsistencyService consistencyService;

    @Autowired
    private DocumentDeletionService documentDeletionService;

    @Autowired
    private VectorStoreJdbcRepository vectorRepository;

    @SpyBean
    private EmbeddingModel embeddingModel;

    private final AtomicBoolean failAll = new AtomicBoolean(false);

    private final AtomicBoolean failMarker = new AtomicBoolean(false);

    private String documentId;

    private final List<String> extraDocumentIds = new ArrayList<>();

    private String source;

    private String title;

    @AfterEach
    void cleanUp() {
        resetFailure();
        for (String extra : extraDocumentIds) {
            try {
                documentDeletionService.deleteDocument(extra);
            } catch (RuntimeException ignored) {
                // 尽力清理；主断言错误优先保留。
            }
        }
        extraDocumentIds.clear();
        if (documentId == null) {
            return;
        }
        try {
            documentDeletionService.deleteDocument(documentId);
        } catch (RuntimeException e) {
            // 测试失败时文档可能已删除；尽力清理残留向量，不掩盖原始断言错误。
            try {
                vectorIndexService.deleteVectorsOnly(documentId);
            } catch (RuntimeException ignored) {
                // 保留现场，供人工排查。
            }
        }
    }

    @Test
    @DisplayName("新增、重复导入/索引、更新切换与删除的完整生命周期")
    void lifecycleNewDuplicateUpdateDelete() {
        installFailureSpy();
        String contentV1 = repeat("D31 设备异常振动排查。", 80);
        KnowledgeDocument imported = importText(contentV1);
        documentId = imported.getDocumentId();
        assertThat(imported.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(imported.getVersion()).isEqualTo(1);
        evidence("lifecycle", "afterNewImport");

        // 重复导入：同文档、同内容，不应新增版本/页面/向量。
        DocumentImportView duplicateImport = importService.importFile(file(contentV1),
                null, source, title);
        assertThat(duplicateImport.isDuplicate()).isTrue();
        assertThat(duplicateImport.getDocument().getDocumentId()).isEqualTo(documentId);
        assertThat(duplicateImport.getDocument().getVersion()).isEqualTo(1);
        evidence("lifecycle", "afterDuplicateImport");

        VectorIndexTaskView first = vectorIndexService.indexDocument(documentId, ChunkStrategy.FIXED_SIZE);
        assertThat(first.getStatus()).isEqualTo("COMPLETED");
        assertThat(first.getChunkCount()).isGreaterThan(1);
        List<VectorChunkRecord> v1Chunks = ledger.listChunks(documentId, first.getVersion());
        assertThat(v1Chunks).hasSize(first.getChunkCount());
        assertThat(v1Chunks).allMatch(c -> documentId.equals(c.getDocumentId()));
        assertThat(v1Chunks).allMatch(c -> first.getVersion().equals(c.getVersion()));
        int v1VectorCount = vectorRepository.countManagedByDocument(documentId);
        assertThat(v1VectorCount).isEqualTo(v1Chunks.size());
        evidence("lifecycle", "afterFirstIndex");

        // 重复索引：稳定 ID + 数据库/ PG 幂等，返回 duplicate=true，向量数不增加。
        VectorIndexTaskView duplicateIndex = vectorIndexService.indexDocument(documentId, ChunkStrategy.FIXED_SIZE);
        assertThat(duplicateIndex.isDuplicate()).isTrue();
        assertThat(vectorRepository.countManagedByDocument(documentId)).isEqualTo(v1VectorCount);
        assertThat(ledger.listTasks(documentId)).hasSize(1);
        evidence("lifecycle", "afterDuplicateIndex");

        // 更新：内容变化 -> version 2。新版本未成功前，旧版本向量保留。
        String contentV2 = repeat("D31 更新后的联轴器同轴度与轴承间隙检查。", 80);
        DocumentImportView updateView = importService.importFile(file(contentV2), null, source, title);
        KnowledgeDocument updated = documentService.getByDocumentId(updateView.getDocument().getDocumentId());
        assertThat(updated.getVersion()).isEqualTo(2);
        documentId = updated.getDocumentId();
        evidence("lifecycle", "afterVersion2Import");
        failAll.set(true);
        VectorIndexTaskView v2Failed = vectorIndexService.indexDocument(documentId, ChunkStrategy.FIXED_SIZE);
        assertThat(v2Failed.getStatus()).isIn("FAILED", "PARTIAL");
        assertThat(ledger.findTask(documentId, "1").getStatus()).isEqualTo(VectorIndexStatus.COMPLETED);
        assertThat(vectorRepository.countManagedByDocument(documentId)).isGreaterThanOrEqualTo(v1VectorCount);
        evidence("lifecycle", "afterVersion2Failed");

        failAll.set(false);
        VectorIndexTaskView v2 = vectorIndexService.retry(documentId, v2Failed.getVersion());
        assertThat(v2.getStatus()).isEqualTo("COMPLETED");
        VectorIndexTask v1Task = ledger.findTask(documentId, "1");
        assertThat(v1Task.getStatus()).isEqualTo(VectorIndexStatus.SUPERSEDED);
        assertThat(ledger.listChunks(documentId, "1")).isEmpty();
        List<String> currentIds = ledger.listChunks(documentId, v2.getVersion()).stream()
                .map(VectorChunkRecord::getChunkId).toList();
        assertThat(vectorRepository.countByIds(currentIds)).isEqualTo(currentIds.size());
        assertThat(vectorRepository.countManagedByDocument(documentId)).isEqualTo(currentIds.size());
        evidence("lifecycle", "afterVersion2Retry");

        // 删除：先 PG 后 MySQL；返回真实删除行数，文档与页面/台账均不存在。
        evidence("lifecycle", "beforeDelete");
        VectorDeleteView deleted = documentDeletionService.deleteDocument(documentId);
        assertThat(deleted.isDocumentDeleted()).isTrue();
        assertThat(deleted.getDeletedVectorRows()).isPositive();
        assertThat(vectorRepository.countManagedByDocument(documentId)).isZero();
        assertThat(ledger.listAllChunks(documentId)).isEmpty();
        assertThatThrownBy(() -> documentService.getByDocumentId(documentId))
                .isInstanceOf(DocumentValidationException.class);
        documentId = null;
    }

    @Test
    @DisplayName("全部 Chunk 失败后重试复用稳定 ID 并恢复")
    void failedThenRetryRecovers() {
        installFailureSpy();
        String content = repeat("D31 全量失败重试验证。", 80);
        documentId = importText(content).getDocumentId();
        failAll.set(true);
        VectorIndexTaskView failed = vectorIndexService.indexDocument(documentId, ChunkStrategy.FIXED_SIZE);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getIndexedChunkCount()).isZero();
        assertThat(vectorRepository.countManagedByDocument(documentId)).isZero();
        System.out.println("D31_FAILED_STATE|status=" + failed.getStatus()
                + "|indexed=" + failed.getIndexedChunkCount()
                + "|total=" + failed.getChunkCount()
                + "|failed=" + failed.getFailedChunkCount()
                + "|vectors=" + vectorRepository.countManagedByDocument(documentId));
        List<String> stableIds = ledger.listChunks(documentId, "1").stream()
                .map(VectorChunkRecord::getChunkId).sorted().toList();
        evidence("failedRetry", "afterAllFailed");

        failAll.set(false);
        VectorIndexTaskView recovered = vectorIndexService.retry(documentId, "1");
        assertThat(recovered.getStatus()).isEqualTo("COMPLETED");
        List<String> afterIds = ledger.listChunks(documentId, "1").stream()
                .map(VectorChunkRecord::getChunkId).sorted().toList();
        assertThat(afterIds).containsExactlyElementsOf(stableIds);
        assertThat(vectorRepository.countByIds(afterIds)).isEqualTo(afterIds.size());
        evidence("failedRetry", "afterRetryRecovered");
    }

    @Test
    @DisplayName("单 Chunk 失败进入 PARTIAL，重试只补失败 Chunk")
    void partialFailureThenRetryOnlyMissing() {
        installFailureSpy();
        String content = repeat("D31 部分失败恢复。", 60)
                + "\n" + MARKER + "\n" + repeat("D31 其余内容继续向量化。", 70);
        documentId = importText(content).getDocumentId();
        failMarker.set(true);
        VectorIndexTaskView partial = vectorIndexService.indexDocument(documentId, ChunkStrategy.FIXED_SIZE);
        assertThat(partial.getStatus()).isIn("PARTIAL", "FAILED");
        int indexedBefore = partial.getIndexedChunkCount();
        int total = partial.getChunkCount();
        assertThat(indexedBefore).isGreaterThan(0).isLessThan(total);
        assertThat(partial.getFailedChunkCount()).isGreaterThan(0);
        System.out.println("D31_PARTIAL_STATE|status=" + partial.getStatus()
                + "|indexed=" + indexedBefore
                + "|total=" + total
                + "|failed=" + partial.getFailedChunkCount()
                + "|vectors=" + vectorRepository.countManagedByDocument(documentId));
        List<String> stableIds = ledger.listChunks(documentId, "1").stream()
                .map(VectorChunkRecord::getChunkId).sorted().toList();
        evidence("partialRetry", "afterPartialFailure");

        failMarker.set(false);
        VectorIndexTaskView recovered = vectorIndexService.retry(documentId, "1");
        assertThat(recovered.getStatus()).isEqualTo("COMPLETED");
        assertThat(recovered.getIndexedChunkCount()).isEqualTo(total);
        List<String> afterIds = ledger.listChunks(documentId, "1").stream()
                .map(VectorChunkRecord::getChunkId).sorted().toList();
        assertThat(afterIds).containsExactlyElementsOf(stableIds);
        assertThat(vectorRepository.countByIds(afterIds)).isEqualTo(total);
        evidence("partialRetry", "afterRetryRecovered");
    }

    @Test
    @DisplayName("一致性核对识别缺失/孤儿并补偿")
    void consistencyDetectsMissingAndOrphanThenRepairs() {
        installFailureSpy();
        String content = repeat("D31 一致性核对与补偿。", 80);
        documentId = importText(content).getDocumentId();
        VectorIndexTaskView task = vectorIndexService.indexDocument(documentId, ChunkStrategy.FIXED_SIZE);
        assertThat(task.getStatus()).isEqualTo("COMPLETED");

        List<VectorChunkRecord> chunks = ledger.listChunks(documentId, task.getVersion());
        String missingId = chunks.get(0).getChunkId();
        vectorRepository.deleteByIds(List.of(missingId));

        String orphanId = UUID.randomUUID().toString();
        VectorStoreRecord orphan = new VectorStoreRecord();
        orphan.setId(orphanId);
        orphan.setContent("orphan-vector-for-d31-consistency");
        orphan.setEmbedding(new float[384]);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("managedBy", "d31");
        metadata.put("documentId", documentId);
        metadata.put("version", "999");
        orphan.setMetadata(metadata);
        vectorRepository.upsert(orphan);

        VectorIndexConsistencyView before = consistencyService.checkDocument(documentId);
        assertThat(before.getStatus()).isEqualTo("FAIL");
        assertThat(before.getMissingVectorCount()).isEqualTo(1);
        assertThat(before.getOrphanVectorCount()).isEqualTo(1);
        System.out.println("D31_CONSISTENCY_STATE|phase=afterTamper|status=" + before.getStatus()
                + "|missing=" + before.getMissingVectorCount()
                + "|orphan=" + before.getOrphanVectorCount()
                + "|findings=" + before.getFindings());
        VectorIndexConsistencyView globalBefore = consistencyService.checkGlobal();
        assertThat(globalBefore.getOrphanVectorCount()).isGreaterThanOrEqualTo(1);
        int globalDeleted = consistencyService.repairGlobalOrphans();
        assertThat(globalDeleted).isGreaterThanOrEqualTo(1);
        System.out.println("D31_GLOBAL_STATE|phase=beforeRepair|orphan=" + globalBefore.getOrphanVectorCount()
                + "|deleted=" + globalDeleted);
        evidence("consistencyRepair", "afterTamper");

        VectorIndexTaskView repaired = consistencyService.repair(documentId);
        assertThat(repaired.getStatus()).isEqualTo("COMPLETED");
        VectorIndexConsistencyView after = consistencyService.checkDocument(documentId);
        assertThat(after.getStatus()).isEqualTo("PASS");
        System.out.println("D31_CONSISTENCY_STATE|phase=afterRepair|status=" + after.getStatus()
                + "|missing=" + after.getMissingVectorCount()
                + "|orphan=" + after.getOrphanVectorCount()
                + "|findings=" + after.getFindings());
        assertThat(vectorRepository.countManagedByDocument(documentId)).isEqualTo(chunks.size());
        // 任务状态被人工改成 COMPLETED 但台账仍有 FAILED Chunk：不能只看任务状态判 PASS。
        ledger.updateChunkStatus(chunks.get(0).getChunkId(), VectorChunkStatus.FAILED);
        VectorIndexConsistencyView ledgerFailure = consistencyService.checkDocument(documentId);
        assertThat(ledgerFailure.getStatus()).isEqualTo("FAIL");
        assertThat(ledgerFailure.getFindings()).contains("CHUNK_NOT_INDEXED");
        System.out.println("D31_CONSISTENCY_STATE|phase=ledgerMismatch|status=" + ledgerFailure.getStatus()
                + "|findings=" + ledgerFailure.getFindings());
        ledger.updateChunkStatus(chunks.get(0).getChunkId(), VectorChunkStatus.INDEXED);
        assertThat(consistencyService.checkDocument(documentId).getStatus()).isEqualTo("PASS");
        evidence("consistencyRepair", "afterRepair");
    }

    @Test
    @DisplayName("不同文档相同内容可各自成档并独立归属向量")
    void sameContentDifferentDocumentsStaySeparate() {
        installFailureSpy();
        String content = repeat("D31 相同内容但不同文档归属验证。", 80);
        KnowledgeDocument first = importWith(content,
                "D31-IT-SHARED-A-" + UUID.randomUUID(), "相同内容文档A");
        KnowledgeDocument second = importWith(content,
                "D31-IT-SHARED-B-" + UUID.randomUUID(), "相同内容文档B");
        extraDocumentIds.add(first.getDocumentId());
        extraDocumentIds.add(second.getDocumentId());

        assertThat(first.getDocumentId()).isNotEqualTo(second.getDocumentId());
        assertThat(first.getContentHash()).isEqualTo(second.getContentHash());
        assertThat(first.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(second.getStatus().name()).isEqualTo("COMPLETED");

        VectorIndexTaskView firstTask = vectorIndexService.indexDocument(
                first.getDocumentId(), ChunkStrategy.FIXED_SIZE);
        VectorIndexTaskView secondTask = vectorIndexService.indexDocument(
                second.getDocumentId(), ChunkStrategy.FIXED_SIZE);
        assertThat(firstTask.getStatus()).isEqualTo("COMPLETED");
        assertThat(secondTask.getStatus()).isEqualTo("COMPLETED");
        assertThat(vectorRepository.listManagedIdsByDocument(first.getDocumentId()))
                .hasSize(firstTask.getChunkCount());
        assertThat(vectorRepository.listManagedIdsByDocument(second.getDocumentId()))
                .hasSize(secondTask.getChunkCount());
        evidenceFor("sameContent", "first", first.getDocumentId());
        evidenceFor("sameContent", "second", second.getDocumentId());
    }

    private void evidenceFor(String scenario, String step, String targetDocumentId) {
        KnowledgeDocument doc;
        try {
            doc = documentService.getByDocumentId(targetDocumentId);
        } catch (RuntimeException e) {
            System.out.println("D31_EVIDENCE|" + scenario + "|" + step
                    + "|documentId=" + targetDocumentId + "|document=DELETED");
            return;
        }
        List<VectorIndexTask> tasks = ledger.listTasks(targetDocumentId);
        List<VectorChunkRecord> currentChunks = ledger.listChunks(targetDocumentId, String.valueOf(doc.getVersion()));
        int vectors = vectorRepository.countManagedByDocument(targetDocumentId);
        System.out.println("D31_EVIDENCE|" + scenario + "|" + step
                + "|documentId=" + targetDocumentId
                + "|documentStatus=" + doc.getStatus()
                + "|documentVersion=" + doc.getVersion()
                + "|chunks=" + ledger.listAllChunks(targetDocumentId).size()
                + "|currentChunkIds=" + currentChunks.stream()
                .map(VectorChunkRecord::getChunkId).toList()
                + "|vectors=" + vectors
                + "|tasks=" + tasks.stream()
                .map(t -> t.getVersion() + ":" + t.getStatus() + ":attempt=" + t.getAttemptCount())
                .toList());
    }

    private void evidence(String scenario, String step) {
        evidenceFor(scenario, step, documentId);
    }

    private KnowledgeDocument importText(String content) {
        source = "D31-IT-" + UUID.randomUUID();
        title = "D31 集成测试文档-" + UUID.randomUUID();
        return importWith(content, source, title);
    }

    private KnowledgeDocument importWith(String content, String importSource, String importTitle) {
        DocumentImportView view = importService.importFile(file(content),
                null, importSource, importTitle);
        return documentService.getByDocumentId(view.getDocument().getDocumentId());
    }

    private MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "d31-it.txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private String repeat(String value, int times) {
        return value.repeat(times);
    }

    private void installFailureSpy() {
        doAnswer((InvocationOnMock invocation) -> {
            String text = invocation.getArgument(0);
            if (failAll.get() || (failMarker.get() && text.contains(MARKER))) {
                throw new IllegalStateException("D31 injected embedding failure");
            }
            return invocation.callRealMethod();
        }).when(embeddingModel).embed(anyString());
    }

    private void resetFailure() {
        failAll.set(false);
        failMarker.set(false);
    }
}

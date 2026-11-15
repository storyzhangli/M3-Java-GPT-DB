package com.zl.d32.document.vector.service;

import com.zl.d32.document.service.DocumentRegistrationService;
import com.zl.d32.document.vector.dto.VectorDeleteView;
import com.zl.d32.document.vector.service.DocumentRowDeletionService;
import com.zl.d32.document.vector.service.DocumentVectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 文档删除编排：先删 PostgreSQL 向量并核对，再在 MySQL 事务删台账/页面/文档。
 *
 * <p>PG 删除失败时保留 MySQL 文档与台账，可再次调用；不允许先删 MySQL 再删 PG，
 * 否则一旦 PG 失败会失去恢复线索。</p>
 */
@Slf4j
@Service
public class DocumentDeletionService {

    private final DocumentRegistrationService documentService;

    private final DocumentVectorIndexService vectorIndexService;

    private final DocumentRowDeletionService rowDeletionService;

    public DocumentDeletionService(DocumentRegistrationService documentService,
                                   DocumentVectorIndexService vectorIndexService,
                                   DocumentRowDeletionService rowDeletionService) {
        this.documentService = documentService;
        this.vectorIndexService = vectorIndexService;
        this.rowDeletionService = rowDeletionService;
    }

    public VectorDeleteView deleteDocument(String documentId) {
        // 先确认文档存在；不存在则直接抛出明确错误，避免只删向量留下无主状态。
        documentService.getByDocumentId(documentId);
        VectorDeleteView view = vectorIndexService.deleteVectorsOnly(documentId);
        rowDeletionService.deleteRows(documentId);
        view.setDocumentDeleted(true);
        log.info("文档及其向量已删除：documentId={}，chunkRows={}，taskRows={}，vectorRows={}",
                documentId, view.getDeletedChunkRows(), view.getDeletedTaskRows(),
                view.getDeletedVectorRows());
        return view;
    }
}

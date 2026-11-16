package com.zl.d33.document.vector.service;

import com.zl.d33.document.service.DocumentPageService;
import com.zl.d33.document.service.DocumentRegistrationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档本地行删除：页面、文档元数据在同一 MySQL 事务内完成。
 *
 * <p>独立于向量删除，保证“先 PG、后 MySQL”的跨系统顺序不被单个本地事务包住。</p>
 */
@Service
public class DocumentRowDeletionService {

    private final DocumentPageService pageService;

    private final DocumentRegistrationService documentService;

    public DocumentRowDeletionService(DocumentPageService pageService,
                                      DocumentRegistrationService documentService) {
        this.pageService = pageService;
        this.documentService = documentService;
    }

    @Transactional
    public void deleteRows(String documentId) {
        pageService.deletePages(documentId);
        documentService.deleteByDocumentId(documentId);
    }
}

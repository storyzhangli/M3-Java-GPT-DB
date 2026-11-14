package com.zl.d31.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d31.document.config.DocumentImportProperties;
import com.zl.d31.document.dto.DocumentRegisterRequest;
import com.zl.d31.document.exception.DocumentValidationException;
import com.zl.d31.document.mapper.KnowledgeDocumentMapper;
import com.zl.d31.document.model.DocumentStatus;
import com.zl.d31.document.model.KnowledgeDocument;
import com.zl.d31.document.po.KnowledgeDocumentPO;
import com.zl.d31.document.support.DocumentConverter;
import com.zl.d31.document.support.StableIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档登记服务：只处理元数据，不接触文件字节。
 *
 * <p>登记后文档处于 {@code PENDING}，等待文件导入驱动后续状态。
 * D31 起无外部 documentId 时，使用 source + title 派生稳定 ID；同一业务身份重复登记
 * 返回既有文档，不生成第二条记录。</p>
 */
@Service
public class DocumentRegistrationService {

    private final KnowledgeDocumentMapper mapper;
    private final DocumentConverter converter;
    private final DocumentImportProperties properties;
    private final StableIdGenerator idGenerator;

    public DocumentRegistrationService(KnowledgeDocumentMapper mapper,
                                       DocumentConverter converter,
                                       DocumentImportProperties properties,
                                       StableIdGenerator idGenerator) {
        this.mapper = mapper;
        this.converter = converter;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    /**
     * 登记新文档，返回领域模型（PENDING）；同一业务身份重复登记按幂等返回既有行。
     */
    @Transactional
    public KnowledgeDocument register(DocumentRegisterRequest request) {
        String source = request.getSource().trim();
        String title = safeTitle(request.getTitle());
        String documentId;

        if (request.getDocumentId() != null && !request.getDocumentId().isBlank()) {
            documentId = request.getDocumentId().trim();
            KnowledgeDocument existing = findByIdOrNull(documentId);
            if (existing != null) {
                throw new DocumentValidationException("文档标识已存在: " + documentId);
            }
        } else {
            documentId = idGenerator.documentId(source, title);
            KnowledgeDocument existing = findByIdOrNull(documentId);
            if (existing != null) {
                return existing;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocumentId(documentId);
        doc.setSource(source);
        doc.setTitle(title);
        doc.setVersion(1);
        doc.setStatus(DocumentStatus.PENDING);
        doc.setContentHash(null);
        doc.setContentLength(0L);
        doc.setErrorCode("");
        doc.setErrorMessage("");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);

        mapper.insert(converter.toPO(doc));
        return doc;
    }

    public KnowledgeDocument getByDocumentId(String documentId) {
        KnowledgeDocumentPO po = mapper.selectOne(
                new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", documentId));
        if (po == null) {
            throw new DocumentValidationException("文档不存在: " + documentId);
        }
        return converter.toDomain(po);
    }

    public List<KnowledgeDocument> listAll() {
        return mapper.selectList(new QueryWrapper<KnowledgeDocumentPO>()
                        .orderByDesc("updated_at"))
                .stream().map(converter::toDomain).toList();
    }

    /**
     * 删除文档元数据（向量与页面由删除编排服务在同一业务流程中先清理）。
     */
    @Transactional
    public void deleteByDocumentId(String documentId) {
        KnowledgeDocumentPO po = mapper.selectOne(
                new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", documentId));
        if (po != null) {
            mapper.deleteById(po.getId());
        }
    }

    private KnowledgeDocument findByIdOrNull(String documentId) {
        return converter.toDomain(mapper.selectOne(
                new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", documentId)));
    }

    private String safeTitle(String title) {
        String trimmed = title.trim();
        int max = properties.getTitleMaxLength();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}

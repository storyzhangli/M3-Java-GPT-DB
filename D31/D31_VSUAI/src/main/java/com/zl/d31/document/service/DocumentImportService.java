package com.zl.d31.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.zl.d31.document.config.DocumentImportProperties;
import com.zl.d31.document.dto.DocumentImportView;
import com.zl.d31.document.exception.DocumentParseException;
import com.zl.d31.document.exception.DocumentValidationException;
import com.zl.d31.document.mapper.KnowledgeDocumentMapper;
import com.zl.d31.document.model.DocumentPageContent;
import com.zl.d31.document.model.DocumentStatus;
import com.zl.d31.document.model.KnowledgeDocument;
import com.zl.d31.document.po.KnowledgeDocumentPO;
import com.zl.d31.document.support.BasicDocumentParser;
import com.zl.d31.document.support.DocumentConverter;
import com.zl.d31.document.support.FileUploadValidator;
import com.zl.d31.document.support.PdfDocumentParser;
import com.zl.d31.document.support.Sha256ContentHasher;
import com.zl.d31.document.support.StableIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档导入服务：服务端校验、内容指纹、稳定文档 ID、状态机驱动与解析。
 *
 * <p>D31 语义变更：</p>
 * <ul>
 *   <li>新文档不再使用随机 UUID，而由 source + title 派生稳定 documentId；</li>
 *   <li>同一 documentId 内容变化时版本递增，旧版本数据保留到新版本向量切换成功；</li>
 *   <li>不同文档只要 documentId/source/title 不同，即使内容相同也可各自成档；</li>
 *   <li>文本文件也写入 page 1，保证后续 Chunk/向量入库有真实页面来源。</li>
 * </ul>
 */
@Service
public class DocumentImportService {

    private static final Logger log = LoggerFactory.getLogger(DocumentImportService.class);

    private final FileUploadValidator validator;
    private final Sha256ContentHasher hasher;
    private final BasicDocumentParser parser;
    private final PdfDocumentParser pdfParser;
    private final DocumentPageService pageService;
    private final KnowledgeDocumentMapper mapper;
    private final DocumentConverter converter;
    private final DocumentImportProperties properties;
    private final StableIdGenerator idGenerator;

    public DocumentImportService(FileUploadValidator validator,
                                 Sha256ContentHasher hasher,
                                 BasicDocumentParser parser,
                                 PdfDocumentParser pdfParser,
                                 DocumentPageService pageService,
                                 KnowledgeDocumentMapper mapper,
                                 DocumentConverter converter,
                                 DocumentImportProperties properties,
                                 StableIdGenerator idGenerator) {
        this.validator = validator;
        this.hasher = hasher;
        this.parser = parser;
        this.pdfParser = pdfParser;
        this.pageService = pageService;
        this.mapper = mapper;
        this.converter = converter;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    /**
     * 导入文件。documentId 非空时关联已登记文档，否则用 source/title 的稳定身份新建。
     */
    @Transactional
    public DocumentImportView importFile(MultipartFile file, String documentId, String source, String title) {
        byte[] bytes = validator.validate(file);
        String contentHash = hasher.hash(bytes);
        LocalDateTime now = LocalDateTime.now();

        KnowledgeDocument existing = findTarget(documentId, source, title, contentHash);
        if (existing != null && sameHash(existing, contentHash)
                && existing.getStatus() == DocumentStatus.COMPLETED) {
            return buildImportView(true, existing);
        }

        KnowledgeDocument doc;
        if (existing != null) {
            doc = existing;
            if (hasContentHash(existing) && !sameHash(existing, contentHash)) {
                // 内容变化：版本递增；旧版本向量保留，等待新版本成功切换后再清理。
                doc.setVersion(nextVersion(existing.getVersion()));
            }
            doc.setStatus(DocumentStatus.PROCESSING);
            doc.setUpdatedAt(now);
        } else {
            doc = newDocument(documentId, source, title, contentHash, now);
        }

        doc.setContentHash(contentHash);
        doc.setContentLength((long) bytes.length);
        doc.setErrorCode("");
        doc.setErrorMessage("");

        DocumentImportView concurrent = persistProcessing(doc);
        if (concurrent != null) {
            return concurrent;
        }

        if (isPdf(file)) {
            handlePdf(bytes, doc, now);
        } else {
            handleText(bytes, doc, now);
        }

        doc.setUpdatedAt(LocalDateTime.now());
        // 按唯一业务键更新终态，避免部分 JDBC/MyBatis 场景下自增主键未回填导致 updateById 静默 0 行。
        mapper.update(converter.toPO(doc),
                new UpdateWrapper<KnowledgeDocumentPO>().eq("document_id", doc.getDocumentId()));
        return buildImportView(false, doc);
    }

    private void handlePdf(byte[] bytes, KnowledgeDocument doc, LocalDateTime now) {
        try {
            List<DocumentPageContent> pages = pdfParser.parse(bytes);
            preparePages(doc, pages, now, "PdfDocumentParser", "PDFBox-3.0.7");
            pageService.replacePages(pages);
            boolean anyUsable = pages.stream().anyMatch(p -> !Boolean.TRUE.equals(p.getBlank()));
            if (anyUsable) {
                doc.setStatus(DocumentStatus.COMPLETED);
            } else {
                // 全部页面无可用文字：扫描型/空文本，明确识别能力边界，不伪造成功。
                markFailed(doc, "NO_EXTRACTABLE_TEXT", "PDF 无可用提取文本，可能为扫描件，需要 OCR");
            }
        } catch (DocumentParseException e) {
            markFailed(doc, "PARSE_FAILED", e.getMessage());
        } catch (RuntimeException e) {
            log.error("PDF 处理发生未预期异常", e);
            markFailed(doc, "PROCESSING_FAILED", "文档处理失败，请稍后重试");
        }
    }

    private void handleText(byte[] bytes, KnowledgeDocument doc, LocalDateTime now) {
        try {
            String text = parser.parse(bytes);
            DocumentPageContent page = new DocumentPageContent();
            page.setDocumentId(doc.getDocumentId());
            page.setSource(doc.getSource());
            page.setTitle(doc.getTitle());
            page.setVersion(doc.getVersion());
            page.setPageNumber(1);
            page.setContent(text);
            page.setContentLength(text.length());
            page.setBlank(false);
            page.setParserName("BasicDocumentParser");
            page.setParserVersion("D31");
            page.setCreatedAt(now);
            page.setUpdatedAt(now);
            pageService.replacePages(List.of(page));
            doc.setStatus(DocumentStatus.COMPLETED);
        } catch (DocumentParseException e) {
            markFailed(doc, "PARSE_FAILED", e.getMessage());
        } catch (RuntimeException e) {
            log.error("文档处理发生未预期异常", e);
            markFailed(doc, "PROCESSING_FAILED", "文档处理失败，请稍后重试");
        }
    }

    private void preparePages(KnowledgeDocument doc, List<DocumentPageContent> pages,
                              LocalDateTime now, String parserName, String parserVersion) {
        for (DocumentPageContent page : pages) {
            page.setDocumentId(doc.getDocumentId());
            page.setSource(doc.getSource());
            page.setTitle(doc.getTitle());
            page.setVersion(doc.getVersion());
            page.setParserName(parserName);
            page.setParserVersion(parserVersion);
            page.setCreatedAt(now);
            page.setUpdatedAt(now);
        }
    }

    private boolean isPdf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && "pdf".equalsIgnoreCase(name.substring(dot + 1));
    }

    /**
     * 定位本次导入的目标文档。
     *
     * <p>显式 documentId 优先；其次 source + title 的稳定身份；只有两者都缺失时，
     * 才退化为内容指纹去重（该路径无法区分内容相同的不同文档，调用方应补业务身份）。</p>
     */
    private KnowledgeDocument findTarget(String documentId, String source, String title, String contentHash) {
        if (documentId != null && !documentId.isBlank()) {
            KnowledgeDocument existing = findByIdOrNull(documentId.trim());
            if (existing == null) {
                throw new DocumentValidationException("文档不存在: " + documentId.trim());
            }
            return existing;
        }
        if (source != null && !source.isBlank() && title != null && !title.isBlank()) {
            return findByIdOrNull(idGenerator.documentId(source, title));
        }
        List<KnowledgeDocument> byHash = findByHash(contentHash);
        return byHash.isEmpty() ? null : byHash.get(0);
    }

    private KnowledgeDocument newDocument(String documentId, String source, String title,
                                          String contentHash, LocalDateTime now) {
        if (documentId != null && !documentId.isBlank()) {
            throw new DocumentValidationException("文档不存在: " + documentId.trim());
        }
        String resolvedSource;
        String resolvedTitle;
        String resolvedId;
        if (source != null && !source.isBlank() && title != null && !title.isBlank()) {
            resolvedSource = source.trim();
            resolvedTitle = safeTitle(title);
            resolvedId = idGenerator.documentId(resolvedSource, resolvedTitle);
        } else {
            // 无业务身份的兼容路径：仅按内容指纹生成；不同文档相同内容无法区分。
            resolvedSource = "unspecified";
            resolvedTitle = "content-" + contentHash.substring(0, Math.min(12, contentHash.length()));
            resolvedId = idGenerator.fileDocumentId(contentHash);
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocumentId(resolvedId);
        doc.setSource(resolvedSource);
        doc.setTitle(resolvedTitle);
        doc.setVersion(1);
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        return doc;
    }

    private String safeTitle(String title) {
        String trimmed = title.trim();
        int max = properties.getTitleMaxLength();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private boolean sameHash(KnowledgeDocument doc, String contentHash) {
        return contentHash != null && contentHash.equals(doc.getContentHash());
    }

    private boolean hasContentHash(KnowledgeDocument doc) {
        return doc.getContentHash() != null && !doc.getContentHash().isBlank();
    }

    private int nextVersion(Integer current) {
        return current == null ? 1 : current + 1;
    }

    /**
     * 兼容路径可能命中多个同内容文档（D31 已允许不同文档相同内容），只取第一条用于去重。
     */
    private List<KnowledgeDocument> findByHash(String contentHash) {
        List<KnowledgeDocumentPO> rows = mapper.selectList(
                new QueryWrapper<KnowledgeDocumentPO>().eq("content_hash", contentHash));
        return rows.stream().map(converter::toDomain).toList();
    }

    private KnowledgeDocument findByIdOrNull(String documentId) {
        KnowledgeDocumentPO po = mapper.selectOne(
                new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", documentId));
        return converter.toDomain(po);
    }

    private DocumentImportView persistProcessing(KnowledgeDocument doc) {
        if (doc.getId() == null) {
            try {
                mapper.insert(converter.toPO(doc));
            } catch (DuplicateKeyException e) {
                // 并发同文档同内容：唯一约束兜底。事务在方法内捕获，既有行不受影响。
                KnowledgeDocument existing = findByIdOrNull(doc.getDocumentId());
                if (existing != null && sameHash(existing, doc.getContentHash())) {
                    return buildImportView(true, existing);
                }
                throw e;
            }
            return null;
        }
        mapper.updateById(converter.toPO(doc));
        return null;
    }

    private void markFailed(KnowledgeDocument doc, String errorCode, String errorMessage) {
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorCode(errorCode);
        doc.setErrorMessage(errorMessage == null ? "" : errorMessage);
    }

    private DocumentImportView buildImportView(boolean duplicate, KnowledgeDocument doc) {
        DocumentImportView view = new DocumentImportView();
        view.setDuplicate(duplicate);
        view.setDocument(converter.toView(doc));
        return view;
    }
}

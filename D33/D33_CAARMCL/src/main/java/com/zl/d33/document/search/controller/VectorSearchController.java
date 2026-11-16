package com.zl.d33.document.search.controller;

import com.zl.d33.common.Result;
import com.zl.d33.document.search.dto.VectorSearchRequest;
import com.zl.d33.document.search.dto.VectorSearchResultView;
import com.zl.d33.document.search.service.DocumentVectorSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 相似度检索入口：Query Embedding + pgvector Top-K 检索（含可选 metadata 过滤）。
 *
 * <p>请求体：{@code {"query": "...", "topK": 5, "documentId": "..."}}，
 * topK 缺省取 app.ai.search.default-top-k（5）。</p>
 */
@RestController
@RequestMapping("/api/v1/document-search")
public class VectorSearchController {

    private final DocumentVectorSearchService searchService;

    public VectorSearchController(DocumentVectorSearchService searchService) {
        this.searchService = searchService;
    }

    /** 相似度检索：默认 Top-5、K 可调，返回片段/来源/真实分数。 */
    @PostMapping("/top-k")
    public Result<VectorSearchResultView> searchTopK(@Valid @RequestBody VectorSearchRequest request) {
        return Result.success(searchService.search(request));
    }
}

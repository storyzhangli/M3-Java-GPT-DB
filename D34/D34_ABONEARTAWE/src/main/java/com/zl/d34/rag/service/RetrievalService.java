package com.zl.d34.rag.service;

import com.zl.d34.document.search.dto.VectorSearchRequest;
import com.zl.d34.document.search.dto.VectorSearchResultView;
import com.zl.d34.document.search.service.DocumentVectorSearchService;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.RetrievalOutcome;
import com.zl.d34.rag.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * RAG 检索服务：Query Embedding → VectorStore → Top-K，返回结构化检索结果。
 *
 * <p>硬性边界：本服务只包装 {@link DocumentVectorSearchService} 的真实 pgvector 链路
 * （与入库同模型同 384 维、cosine {@code <=>}、HNSW），<b>禁止直接调用 ChatModel</b>；
 * 空命中返回空列表，与检索失败（抛异常）在语义上严格分离，由编排器分别映射
 * {@code NO_HIT} 与 {@code RETRIEVAL_FAILED}。</p>
 */
@Slf4j
@Service
public class RetrievalService {

    private final DocumentVectorSearchService searchService;

    private final RagProperties properties;

    public RetrievalService(DocumentVectorSearchService searchService,
                            RagProperties properties) {
        this.searchService = searchService;
        this.properties = properties;
    }

    /**
     * 执行真实向量检索并映射为 RAG 契约结构体。
     *
     * @param query      用户问题（非空白）
     * @param topK       返回条数；为空取默认 5，越界由检索服务治理收敛
     * @param documentId 可选 metadata 过滤（documentId 为真实入库字段）
     * @return 检索结果（命中为空列表表示检索成功但零命中）
     * @throws RuntimeException 向量库/Embedding 基础设施异常原样抛出，由编排器归类
     */
    public RetrievalOutcome retrieve(String query, Integer topK, String documentId) {
        VectorSearchRequest request = new VectorSearchRequest();
        request.setQuery(query);
        request.setTopK(topK == null ? properties.getDefaultTopK() : topK);
        if (StringUtils.hasText(documentId)) {
            request.setDocumentId(documentId.trim());
        }
        VectorSearchResultView view = searchService.search(request);
        List<RetrievedChunk> hits = view.getHits() == null
                ? List.of()
                : view.getHits().stream().map(RetrievedChunk::from).toList();
        RetrievalOutcome outcome = new RetrievalOutcome(hits, view.getTopK(), view.getElapsedMs(),
                view.getQueryDimensions(), view.getAppliedFilterExpression());
        log.info("RAG 检索完成：query={}，topK={}，命中={}，elapsedMs={}",
                view.getQuery(), view.getTopK(), hits.size(), view.getElapsedMs());
        return outcome;
    }
}

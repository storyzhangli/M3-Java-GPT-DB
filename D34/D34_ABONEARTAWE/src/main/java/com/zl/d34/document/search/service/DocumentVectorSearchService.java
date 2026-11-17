package com.zl.d34.document.search.service;

import com.zl.d34.document.embedding.service.DocumentEmbeddingService;
import com.zl.d34.document.search.config.SearchProperties;
import com.zl.d34.document.search.dto.VectorSearchHitView;
import com.zl.d34.document.search.dto.VectorSearchRequest;
import com.zl.d34.document.search.dto.VectorSearchResultView;
import com.zl.d34.document.search.exception.VectorSearchException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档相似度检索服务：复用入库侧同一条 Embedding 链路与 PgVectorStore 执行 Top-K 检索。
 *
 * <p>检索路径（不绕过现有架构、不重复编写数据库检索层）：</p>
 * <ol>
 *   <li>Query Embedding：经 {@link DocumentEmbeddingService#embedQuery(String)}（同一 EmbeddingModel、
 *       同一重试执行器与维度校验器，与 入库向量处于同一 384 维向量空间）；</li>
 *   <li>相似度检索：调用 Spring AI 1.1.8 {@link VectorStore#similaritySearch(SearchRequest)}，
 *       由 PgVectorStore 以 cosine 距离 {@code embedding <=> ?} 在 PostgreSQL/pgvector 执行
 *       HNSW 近似最近邻查询并返回按相似度降序的 Top-K；</li>
 *   <li>分数语义（经字节码核验）：Document.score = 1.0 - cosine distance，
 *       原始距离保存在 Document metadata 的 distance 键。</li>
 * </ol>
 */
@Slf4j
@Service
public class DocumentVectorSearchService {

    /** PgVectorStore 写入 Document metadata 的原始距离键（DocumentMetadata.DISTANCE.value()）。 */
    static final String METADATA_DISTANCE_KEY = "distance";

    private final VectorStore vectorStore;

    private final DocumentEmbeddingService embeddingService;

    private final SearchProperties properties;

    public DocumentVectorSearchService(VectorStore vectorStore,
                                       DocumentEmbeddingService embeddingService,
                                       SearchProperties properties) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    /**
     * 执行相似度检索：Query Embedding → VectorStore.similaritySearch → 结果视图组装。
     *
     * @param request 检索请求（query 必填，topK 可选默认 5，documentId 可选过滤）
     * @return 检索结果视图（含真实分数与 metadata）
     */
    public VectorSearchResultView search(VectorSearchRequest request) {
        long start = System.currentTimeMillis();
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            throw VectorSearchException.badRequest("query 不能为空");
        }
        String query = request.getQuery().trim();
        if (query.length() > properties.getMaxQueryLength()) {
            throw VectorSearchException.badRequest(
                    "query 长度 " + query.length() + " 超过上限 " + properties.getMaxQueryLength());
        }
        int topK = resolveTopK(request.getTopK());

        // Query Embedding：与入库同模型同维度，前置校验失败即拒绝，不进入向量库查询。
        float[] queryVector = embeddingService.embedQuery(query);
        if (queryVector == null || queryVector.length == 0) {
            throw VectorSearchException.badRequest("Query Embedding 返回空向量");
        }

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                // 暂不设相似度阈值：本迭代保持默认 0.0（不过滤），
                // 阈值建议待 10 问题评估的真实分数分布产出后另行决策。
                .similarityThresholdAll();
        if (StringUtils.hasText(request.getDocumentId())) {
            builder.filterExpression(new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key("documentId"),
                    new Filter.Value(request.getDocumentId().trim())));
        }
        SearchRequest searchRequest = builder.build();

        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        long elapsed = System.currentTimeMillis() - start;

        List<VectorSearchHitView> hits = new ArrayList<>(documents == null ? 0 : documents.size());
        int rank = 0;
        if (documents != null) {
            for (Document document : documents) {
                rank++;
                hits.add(toHit(rank, document));
            }
        }

        VectorSearchResultView view = new VectorSearchResultView();
        view.setQuery(query);
        view.setTopK(topK);
        view.setAppliedFilterExpression(StringUtils.hasText(request.getDocumentId())
                ? "documentId == '" + request.getDocumentId().trim() + "'" : null);
        view.setQueryDimensions(queryVector.length);
        view.setElapsedMs(elapsed);
        view.setHitCount(hits.size());
        view.setHits(hits);
        log.info("相似度检索完成：topK={}，命中={}，queryDimensions={}，elapsedMs={}，filter={}",
                topK, hits.size(), queryVector.length, elapsed, view.getAppliedFilterExpression());
        return view;
    }

    /**
     * K 值治理：空取默认 5；越界收敛到 [1, maxTopK]，防止一次拉全库。
     */
    private int resolveTopK(Integer requested) {
        int topK = requested == null ? properties.getDefaultTopK() : requested;
        if (topK < 1) {
            throw VectorSearchException.badRequest("topK 必须 >= 1");
        }
        if (topK > properties.getMaxTopK()) {
            throw VectorSearchException.badRequest("topK " + topK + " 超过上限 " + properties.getMaxTopK());
        }
        return topK;
    }

    /**
     * Document → 命中视图：分数取自 PgVectorStore 真实计算结果，metadata 原样透传。
     */
    private VectorSearchHitView toHit(int rank, Document document) {
        VectorSearchHitView hit = new VectorSearchHitView();
        hit.setRank(rank);
        hit.setContent(document.getText());
        hit.setScore(document.getScore());
        Map<String, Object> metadata = document.getMetadata();
        hit.setMetadata(metadata);
        Object distance = metadata.get(METADATA_DISTANCE_KEY);
        hit.setCosineDistance(distance instanceof Number number ? number.doubleValue() : null);
        hit.setVectorId(document.getId());
        hit.setDocumentId(asString(metadata.get("documentId")));
        hit.setVersion(asString(metadata.get("version")));
        hit.setTitle(asString(metadata.get("title")));
        hit.setSource(asString(metadata.get("source")));
        hit.setChunkIndex(asInteger(metadata.get("chunkIndex")));
        hit.setModelName(asString(metadata.get("modelName")));
        hit.setDimensions(asInteger(metadata.get("dimensions")));
        return hit;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

package com.zl.d32.document.search;

import com.zl.d32.document.embedding.service.DocumentEmbeddingService;
import com.zl.d32.document.search.config.SearchProperties;
import com.zl.d32.document.search.dto.VectorSearchRequest;
import com.zl.d32.document.search.dto.VectorSearchResultView;
import com.zl.d32.document.search.exception.VectorSearchException;
import com.zl.d32.document.search.service.DocumentVectorSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 相似度检索服务单元测试：不依赖 Spring 上下文与真实数据库。
 *
 * <p>覆盖：请求校验、Top-K 默认与边界、metadata 过滤表达式构造、
 * Document → 命中视图映射（真实分数透传）、空结果防御。</p>
 */
class DocumentVectorSearchServiceTest {

    private VectorStore vectorStore;

    private DocumentEmbeddingService embeddingService;

    private DocumentVectorSearchService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        embeddingService = mock(DocumentEmbeddingService.class);
        SearchProperties properties = new SearchProperties();
        properties.validate();
        service = new DocumentVectorSearchService(vectorStore, embeddingService, properties);
    }

    private void stubEmbedding(float[] vector) {
        when(embeddingService.embedQuery(anyString())).thenReturn(vector);
    }

    private Document document(String id, String text, double score, Map<String, Object> metadata) {
        return Document.builder().id(id).text(text).metadata(metadata).score(score).build();
    }

    @Test
    @DisplayName("空查询、空白查询与超长查询均被拒绝且不触发向量库调用")
    void rejectBlankAndOverlongQuery() {
        VectorSearchRequest blank = new VectorSearchRequest();
        blank.setQuery("   ");
        assertThatThrownBy(() -> service.search(blank))
                .isInstanceOfSatisfying(VectorSearchException.class,
                        e -> assertThat(e.isBadRequest()).isTrue());

        VectorSearchRequest overlong = new VectorSearchRequest();
        overlong.setQuery("长".repeat(9000));
        assertThatThrownBy(() -> service.search(overlong))
                .isInstanceOfSatisfying(VectorSearchException.class,
                        e -> assertThat(e.isBadRequest()).isTrue());

        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("topK 为空取默认 5，越界（0/超上限）被拒绝")
    void topKDefaultAndBounds() {
        stubEmbedding(new float[384]);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        VectorSearchRequest request = new VectorSearchRequest();
        request.setQuery("主轴过热怎么处理");
        service.search(request);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getTopK()).isEqualTo(5);
        assertThat(captor.getValue().getFilterExpression()).isNull();

        VectorSearchRequest zero = new VectorSearchRequest();
        zero.setQuery("q");
        zero.setTopK(0);
        assertThatThrownBy(() -> service.search(zero))
                .isInstanceOfSatisfying(VectorSearchException.class,
                        e -> assertThat(e.isBadRequest()).isTrue());

        VectorSearchRequest over = new VectorSearchRequest();
        over.setQuery("q");
        over.setTopK(21);
        assertThatThrownBy(() -> service.search(over))
                .isInstanceOfSatisfying(VectorSearchException.class,
                        e -> assertThat(e.isBadRequest()).isTrue());
    }

    @Test
    @DisplayName("documentId 过滤生成 EQ 表达式，Key 为 metadata 字段名")
    void documentIdFilterExpression() {
        stubEmbedding(new float[384]);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        VectorSearchRequest request = new VectorSearchRequest();
        request.setQuery("空压机高温");
        request.setDocumentId("DOC-AIRCOMP-VSD-005");
        service.search(request);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        Filter.Expression expression = captor.getValue().getFilterExpression();
        assertThat(expression).isNotNull();
        assertThat(expression.type()).isEqualTo(Filter.ExpressionType.EQ);
        assertThat(((Filter.Key) expression.left()).key()).isEqualTo("documentId");
        assertThat(((Filter.Value) expression.right()).value()).isEqualTo("DOC-AIRCOMP-VSD-005");
    }

    @Test
    @DisplayName("结果映射：score 透传、distance 来自 metadata、rank 升序、metadata 原样保留")
    void mapDocumentsToHits() {
        stubEmbedding(new float[384]);
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("distance", 0.123f);
        meta1.put("documentId", "DOC-CNC-COOLING-001");
        meta1.put("version", "1");
        meta1.put("title", "数控机床冷却系统维护手册（教学夹具）");
        meta1.put("source", "d32-search-it");
        meta1.put("chunkIndex", 2);
        meta1.put("modelName", "local-hashing-embedding");
        meta1.put("dimensions", 384);
        Map<String, Object> meta2 = new HashMap<>(meta1);
        meta2.put("distance", 0.456f);
        meta2.put("chunkIndex", 3);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("id-1", "片段一", 0.877d, meta1),
                document("id-2", "片段二", 0.544d, meta2)));

        VectorSearchRequest request = new VectorSearchRequest();
        request.setQuery("主轴过热");
        VectorSearchResultView view = service.search(request);

        assertThat(view.getHitCount()).isEqualTo(2);
        assertThat(view.getQueryDimensions()).isEqualTo(384);
        assertThat(view.getHits()).hasSize(2);
        assertThat(view.getHits().get(0).getRank()).isEqualTo(1);
        assertThat(view.getHits().get(0).getScore()).isEqualTo(0.877d);
        assertThat(view.getHits().get(0).getCosineDistance()).isEqualTo(0.123d, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(view.getHits().get(0).getDocumentId()).isEqualTo("DOC-CNC-COOLING-001");
        assertThat(view.getHits().get(0).getChunkIndex()).isEqualTo(2);
        assertThat(view.getHits().get(0).getModelName()).isEqualTo("local-hashing-embedding");
        assertThat(view.getHits().get(0).getDimensions()).isEqualTo(384);
        assertThat(view.getHits().get(0).getMetadata()).containsKey("distance");
        assertThat(view.getHits().get(1).getRank()).isEqualTo(2);
        assertThat(view.getHits().get(1).getCosineDistance()).isEqualTo(0.456d, org.assertj.core.data.Offset.offset(1e-6));
        // score = 1 - distance 的不变量在视图层保持可验证（float→double 转换误差在 1e-6 内）
        assertThat(view.getHits().get(0).getScore())
                .isEqualTo(1.0d - view.getHits().get(0).getCosineDistance(),
                        org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("Query Embedding 校验失败（空向量）时拒绝检索")
    void rejectEmptyQueryVector() {
        when(embeddingService.embedQuery(anyString())).thenReturn(new float[0]);
        VectorSearchRequest request = new VectorSearchRequest();
        request.setQuery("任意问题");
        assertThatThrownBy(() -> service.search(request))
                .isInstanceOfSatisfying(VectorSearchException.class,
                        e -> assertThat(e.isBadRequest()).isTrue());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }
}

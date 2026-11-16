package com.zl.d33.rag;

import com.zl.d33.rag.config.RagProperties;
import com.zl.d33.rag.dto.RagQueryRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.rag.model.RetrievalOutcome;
import com.zl.d33.rag.model.RetrievedChunk;
import com.zl.d33.rag.service.ContextAssemblyService;
import com.zl.d33.rag.service.GenerationService;
import com.zl.d33.rag.service.RagOrchestrator;
import com.zl.d33.rag.service.RetrievalService;
import com.zl.d33.service.AiChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RAG 场景 4：无可用上下文——检索有结果但全被过滤（空白内容）/预算无法容纳，
 * 返回 NO_USABLE_CONTEXT，不调模型。
 */
@ExtendWith(MockitoExtension.class)
class RagNoUsableContextTest {

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private AiChatService aiChatService;

    private RagOrchestrator orchestrator;

    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        GenerationService generationService = new GenerationService(aiChatService, properties);
        orchestrator = new RagOrchestrator(retrievalService, new ContextAssemblyService(),
                generationService, properties, "mock");
    }

    @Test
    void allHitsBlankReturnsNoUsableContextWithoutInvokingModel() {
        RetrievedChunk blank1 = RagTestFixtures.chunk("v1", "doc-x", "c1", "手册X", 0, 0.9d, "   ");
        RetrievedChunk blank2 = RagTestFixtures.chunk("v2", "doc-x", "c2", "手册X", 1, 0.8d, null);
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(blank1, blank2), 5, 6L, 384, null));

        RagResponse response = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.NO_USABLE_CONTEXT.name(), response.status());
        assertEquals(properties.getNoUsableContextAnswer(), response.answer());
        assertTrue(response.sources().isEmpty());
        assertFalse(response.generation().invoked());
        // 检索有命中（2 条），但无可用上下文——与 NO_HIT（0 命中）可区分
        assertEquals(2, response.retrieval().hitCount());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void budgetCannotAccommodateAnyChunkReturnsNoUsableContext() {
        properties.setContextCharBudget(0);
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-x", "c1", "手册X", 0, 0.9d, "内容");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(hit), 5, 6L, 384, null));

        RagResponse response = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.NO_USABLE_CONTEXT.name(), response.status());
        assertFalse(response.generation().invoked());
        verifyNoInteractions(aiChatService);
    }

    private RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

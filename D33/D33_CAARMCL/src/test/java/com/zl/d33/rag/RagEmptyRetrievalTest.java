package com.zl.d33.rag;

import com.zl.d33.rag.config.RagProperties;
import com.zl.d33.rag.dto.RagQueryRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.rag.model.RetrievalOutcome;
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
 * RAG 场景 3：空检索——检索成功但零命中，返回 NO_HIT，且 ChatModel 零调用
 * （Mockito verifyNoInteractions + 响应 generation.invoked=false 双重可核验证据）。
 */
@ExtendWith(MockitoExtension.class)
class RagEmptyRetrievalTest {

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
    void emptyHitReturnsNoHitWithoutInvokingModel() {
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(), 5, 8L, 384, null));

        RagResponse response = orchestrator.ask(request("没有对应知识的问题"));

        assertEquals(RagStatus.NO_HIT.name(), response.status());
        assertEquals(properties.getNoHitAnswer(), response.answer());
        assertTrue(response.sources().isEmpty());
        // 证据 1：响应级可核验——生成环节未触发
        assertFalse(response.generation().invoked());
        assertEquals(0, response.retrieval().hitCount());
        // 证据 2：Mockito 零交互——AiChatService（ChatModel 唯一抽象）全程未被触碰
        verifyNoInteractions(aiChatService);
    }

    @Test
    void noHitIsDistinguishableFromRetrievalFailure() {
        // 空命中（NO_HIT）与异常（RETRIEVAL_FAILED）状态码可区分：本用例固定 NO_HIT 语义
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(), 5, 3L, 384, null));

        RagResponse response = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.NO_HIT.name(), response.status());
        assertEquals(RagStatus.NO_HIT.getDescription(), response.message());
        assertFalse(response.generation().invoked());
        verifyNoInteractions(aiChatService);
    }

    private RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

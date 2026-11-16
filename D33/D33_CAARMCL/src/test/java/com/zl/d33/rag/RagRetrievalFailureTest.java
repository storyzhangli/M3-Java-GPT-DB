package com.zl.d33.rag;

import com.zl.d33.rag.config.RagProperties;
import com.zl.d33.rag.dto.RagQueryRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
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
 * RAG 场景 6：向量库异常——基础设施异常返回 RETRIEVAL_FAILED，
 * 不伪装成空检索（与 NO_HIT 状态码可区分），且不调用模型。
 */
@ExtendWith(MockitoExtension.class)
class RagRetrievalFailureTest {

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
    void vectorStoreFailureReturnsRetrievalFailedNotEmptyHit() {
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenThrow(new RuntimeException("Connection to 192.168.200.153:5432 refused"));

        RagResponse response = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.RETRIEVAL_FAILED.name(), response.status());
        assertEquals(properties.getRetrievalFailedMessage(), response.answer());
        assertTrue(response.sources().isEmpty());
        assertFalse(response.generation().invoked());
        // 检索摘要 hitCount=0 是「未完成检索」语义，由 status 与 NO_HIT 区分
        assertEquals(0, response.retrieval().hitCount());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void statusMessageDoesNotLeakInternalStack() {
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenThrow(new RuntimeException("Internal PgVectorStore stack trace detail"));

        RagResponse response = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.RETRIEVAL_FAILED.name(), response.status());
        // 对外文案不含异常信息与连接细节
        assertFalse(response.answer().contains("PgVectorStore"));
        assertFalse(response.message().contains("stack"));
        assertTrue(response.generation().model() == null);
    }

    private RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

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
import com.zl.d33.service.ChatInvocation;
import com.zl.d33.service.ThinkingSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * RAG 场景 5：模型超时——超时可识别，返回 GENERATION_FAILED，不伪装成正常回答。
 *
 * <p>可控超时：Mockito 即时抛出超时异常模拟模型调用超时，不依赖真实网络等待；
 * 超时分支可稳定复现（每次运行行为一致）。</p>
 */
@ExtendWith(MockitoExtension.class)
class RagGenerationTimeoutTest {

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
                generationService, properties, "deepseek");
    }

    @Test
    void timeoutDuringGenerationReturnsGenerationFailedNotFakeAnswer() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-x", "c1", "手册X", 0, 0.9d, "有效内容");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(hit), 5, 10L, 384, null));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenThrow(new RuntimeException("Read timed out after 30000ms"));

        RagResponse response = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.GENERATION_FAILED.name(), response.status());
        assertEquals(properties.getGenerationFailedMessage(), response.answer());
        assertTrue(response.sources().isEmpty());
        // 生成确实被尝试过（invoked=true），与拒答分支（invoked=false）可区分
        assertTrue(response.generation().invoked());
        // 检索成功与生成失败可区分：检索摘要正常，状态为 GENERATION_FAILED
        assertEquals(1, response.retrieval().hitCount());
    }

    @Test
    void timeoutBranchIsStableAndRepeatable() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-x", "c1", "手册X", 0, 0.9d, "有效内容");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(hit), 5, 10L, 384, null));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenThrow(new RuntimeException("read timeout"))
                .thenThrow(new RuntimeException("read timeout"));

        RagResponse first = orchestrator.ask(request("问题"));
        RagResponse second = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.GENERATION_FAILED.name(), first.status());
        assertEquals(RagStatus.GENERATION_FAILED.name(), second.status());
    }

    private RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

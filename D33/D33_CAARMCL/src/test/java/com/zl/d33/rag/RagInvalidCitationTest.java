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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * RAG 场景 7：引用不存在——模型返回未分配的 [S99]，非法引用被 CitationRegistry 剥离并计数，
 * 来源列表无虚假来源（结构化来源只能由后端注册表组装）。
 */
@ExtendWith(MockitoExtension.class)
class RagInvalidCitationTest {

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
        // deepseek 模式：不追加服务端编号行，模型原始输出即被测对象
        orchestrator = new RagOrchestrator(retrievalService, new ContextAssemblyService(),
                generationService, properties, "deepseek");
    }

    @Test
    void unknownMarkerStrippedAndCountedWithoutFakeSource() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-x", "c1", "手册X", 0, 0.9d, "真实内容");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(hit), 5, 10L, 384, null));
        // 模型输出：合法 [S1] + 非法 [S99]（未分配编号）
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("根据资料 [S1]，答案如此。[S99] 这是编造引用。"));

        RagResponse response = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.SUCCESS.name(), response.status());
        // 非法引用被剥离，合法引用保留
        assertFalse(response.answer().contains("[S99]"));
        assertTrue(response.answer().contains("[S1]"));
        // 剥离计数可核验
        assertEquals(1, response.generation().droppedUnknownMarkers());
        // 来源列表只含真实分配的 S1，无 S99 虚假来源
        assertEquals(1, response.sources().size());
        assertEquals("S1", response.sources().get(0).citationId());
        assertEquals("c1", response.sources().get(0).chunkId());
    }

    @Test
    void onlyFabricatedMarkersMeansEmptySourceList() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-x", "c1", "手册X", 0, 0.9d, "真实内容");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(hit), 5, 10L, 384, null));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("参见文档第 99 页 [S99] 与链接 [S88]。"));

        RagResponse response = orchestrator.ask(request("问题"));

        assertFalse(response.answer().contains("[S99]"));
        assertFalse(response.answer().contains("[S88]"));
        assertEquals(2, response.generation().droppedUnknownMarkers());
        assertTrue(response.sources().isEmpty());
    }

    private RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

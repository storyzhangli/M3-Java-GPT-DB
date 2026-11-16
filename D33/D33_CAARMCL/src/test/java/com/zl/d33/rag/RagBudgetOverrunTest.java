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
 * RAG 场景 9：预算超限——内容总量超预算，最终上下文不超出预算且来源映射不丢
 * （进入上下文的片段来源完整可回溯，被截断的片段有记录）。
 */
@ExtendWith(MockitoExtension.class)
class RagBudgetOverrunTest {

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private AiChatService aiChatService;

    private RagOrchestrator orchestrator;

    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setContextCharBudget(40);
        GenerationService generationService = new GenerationService(aiChatService, properties);
        orchestrator = new RagOrchestrator(retrievalService, new ContextAssemblyService(),
                generationService, properties, "deepseek");
    }

    @Test
    void contextWithinBudgetAndSourceMappingIntact() {
        // 每条 20 字符，预算 40：前两条进入、第三条被截断
        String content1 = RagTestFixtures.repeat("甲", 20);
        String content2 = RagTestFixtures.repeat("乙", 20);
        String content3 = RagTestFixtures.repeat("丙", 20);
        RetrievedChunk c1 = RagTestFixtures.chunk("v1", "doc-1", "chunk-1", "手册一", 0, 0.9d, content1);
        RetrievedChunk c2 = RagTestFixtures.chunk("v2", "doc-2", "chunk-2", "手册二", 0, 0.8d, content2);
        RetrievedChunk c3 = RagTestFixtures.chunk("v3", "doc-3", "chunk-3", "手册三", 0, 0.7d, content3);
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(c1, c2, c3), 5, 20L, 384, null));
        // 模型引用进入上下文的两条来源
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("答案基于 [S1] 与 [S2]。"));

        RagResponse response = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.SUCCESS.name(), response.status());
        // 最终上下文不超出预算（40 字符）
        assertEquals(40, response.generation().contextChars());
        assertTrue(response.generation().truncated());
        // 来源映射不丢：两条来源与进入上下文的 Chunk 对得上（documentId/chunkId 一致）
        assertEquals(2, response.sources().size());
        assertEquals("chunk-1", response.sources().get(0).chunkId());
        assertEquals("doc-1", response.sources().get(0).documentId());
        assertEquals("chunk-2", response.sources().get(1).chunkId());
        assertEquals("doc-2", response.sources().get(1).documentId());
        // 被截断的第三条不产生虚假来源
        assertTrue(response.sources().stream().noneMatch(s -> "chunk-3".equals(s.chunkId())));
    }

    @Test
    void assemblyDroppedCountReflectsTruncation() {
        RetrievedChunk c1 = RagTestFixtures.chunk("v1", "doc-1", "chunk-1", "手册一", 0, 0.9d,
                RagTestFixtures.repeat("A", 30));
        RetrievedChunk c2 = RagTestFixtures.chunk("v2", "doc-2", "chunk-2", "手册二", 0, 0.8d,
                RagTestFixtures.repeat("B", 30));
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(c1, c2), 5, 20L, 384, null));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("答案 [S1]"));

        ContextAssemblyService assembly = new ContextAssemblyService();
        var context = assembly.assemble(List.of(c1, c2), 40);

        // 预算 40：第一条 30 进入、第二条 30 超预算被丢弃并计数
        assertEquals(1, context.chunks().size());
        assertEquals(30, context.totalChars());
        assertEquals(1, context.droppedCount());
        assertTrue(context.truncated());

        RagResponse response = orchestrator.ask(request("问题"));
        assertEquals(30, response.generation().contextChars());
        assertTrue(response.generation().truncated());
    }

    private RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

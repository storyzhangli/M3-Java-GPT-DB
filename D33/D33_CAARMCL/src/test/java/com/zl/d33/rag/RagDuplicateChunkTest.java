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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 场景 8：重复 Chunk——检索结果含重复记录，上下文不重复拼接
 * （组装去重后进入模型的上下文块中重复内容只出现一次）。
 */
@ExtendWith(MockitoExtension.class)
class RagDuplicateChunkTest {

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
    void duplicateChunkNotRepeatedInAssembledContext() {
        String uniqueContent = "唯一片段内容-液压泵检查流程";
        RetrievedChunk first = RagTestFixtures.chunk("v-dup", "doc-x", "c1", "手册X", 0, 0.9d, uniqueContent);
        RetrievedChunk duplicate = RagTestFixtures.chunk("v-dup", "doc-x", "c1", "手册X", 0, 0.7d, uniqueContent);
        RetrievedChunk other = RagTestFixtures.chunk("v2", "doc-x", "c2", "手册X", 1, 0.6d, "另一片段内容");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(first, duplicate, other), 5, 12L, 384, null));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("答案 [S1]"));

        orchestrator.ask(request("问题"));

        ArgumentCaptor<ChatInvocation> captor = ArgumentCaptor.forClass(ChatInvocation.class);
        verify(aiChatService).chat(captor.capture(), any(ThinkingSettings.class));
        String systemPrompt = captor.getValue().systemPrompt();
        // 重复片段在上下文中只出现一次（不重复拼接）
        assertEquals(1, countOccurrences(systemPrompt, uniqueContent));
        // 两条去重后的真实片段都进入上下文
        assertEquals(1, countOccurrences(systemPrompt, "另一片段内容"));
        // 分配编号为 S1、S2（去重后两条）
        assertEquals(1, countOccurrences(systemPrompt, "[S1] 文档"));
        assertEquals(1, countOccurrences(systemPrompt, "[S2] 文档"));
        assertEquals(-1, systemPrompt.indexOf("[S3] 文档"));
    }

    @Test
    void sourcesOnlyFromUniqueChunks() {
        RetrievedChunk first = RagTestFixtures.chunk("v-dup", "doc-x", "c1", "手册X", 0, 0.9d, "片段A");
        RetrievedChunk duplicate = RagTestFixtures.chunk("v-dup", "doc-x", "c1", "手册X", 0, 0.7d, "片段A");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(first, duplicate), 5, 12L, 384, null));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("答案引用 [S1]。"));

        RagResponse response = orchestrator.ask(request("问题"));

        assertEquals(RagStatus.SUCCESS.name(), response.status());
        assertEquals(1, response.sources().size());
        assertEquals("S1", response.sources().get(0).citationId());
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

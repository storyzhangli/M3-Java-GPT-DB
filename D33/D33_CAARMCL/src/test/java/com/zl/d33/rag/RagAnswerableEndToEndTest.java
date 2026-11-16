package com.zl.d33.rag;

import com.zl.d33.rag.config.RagProperties;
import com.zl.d33.rag.dto.RagQueryRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagSource;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * RAG 场景 1：文档可答——检索到相关知识、回答有证据支持、来源可追溯（documentId/chunkId 对得上）。
 *
 * <p>Mock 边界说明：检索服务与生成模型为 Mock（离线单测不依赖真实上游），
 * ContextAssemblyService 与 RagOrchestrator 为真实对象；真实数据库/模型端到端由门控集成测试承担，
 * 本类不冒充真实端到端。</p>
 */
@ExtendWith(MockitoExtension.class)
class RagAnswerableEndToEndTest {

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private AiChatService aiChatService;

    private RagOrchestrator orchestrator;

    private GenerationService generationService;

    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        generationService = new GenerationService(aiChatService, properties);
        // deepseek 模式：不追加服务端编号行，模型原始输出即被测对象（引用清洗不受编号行干扰）
        orchestrator = new RagOrchestrator(retrievalService, new ContextAssemblyService(),
                generationService, properties, "deepseek");
    }

    @Test
    void answerableQuestionReturnsSuccessWithTraceableSources() {
        RetrievedChunk hit1 = RagTestFixtures.chunk("v1", "doc-hydraulic", "c1", "液压机操作手册", 0, 0.87d,
                "液压机压力不足时，应先检查液压泵输出与溢流阀设定值。");
        RetrievedChunk hit2 = RagTestFixtures.chunk("v2", "doc-hydraulic", "c2", "液压机操作手册", 1, 0.72d,
                "油温过高会导致油液粘度下降，进而造成系统压力波动。");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(hit1, hit2), 5, 42L, 384, null));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("压力不足应先检查液压泵输出与溢流阀设定值。[S1]"));

        RagResponse response = orchestrator.ask(request("液压机压力不足怎么办？"));

        assertEquals(RagStatus.SUCCESS.name(), response.status());
        assertNotNull(response.answer());
        assertTrue(response.answer().contains("[S1]"));
        assertFalse(response.answer().contains("[S99]"));
        // 来源与真实检索命中一致：S1 → doc-hydraulic/c1
        assertEquals(1, response.sources().size());
        RagSource source = response.sources().get(0);
        assertEquals("S1", source.citationId());
        assertEquals("doc-hydraulic", source.documentId());
        assertEquals("c1", source.chunkId());
        assertEquals("液压机操作手册", source.title());
        assertEquals(0.87d, source.score(), 1e-9);
        // 检索摘要与生成摘要字段齐全
        assertEquals(2, response.retrieval().hitCount());
        assertEquals(384, response.retrieval().queryDimensions());
        assertTrue(response.generation().invoked());
        assertFalse(response.generation().truncated());
    }

    @Test
    void mockModeAppendsServerSideMarkerLineSoOfflineLoopIsDemonstrable() {
        // 单独构造 mock 模式编排器：离线闭环由服务端按真实注册表追加编号行
        RagOrchestrator mockOrchestrator = new RagOrchestrator(retrievalService,
                new ContextAssemblyService(), generationService, properties, "mock");
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-x", "c1", "手册X", 0, 0.9d, "内容X");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(hit), 5, 10L, 384, null));
        // mock 模型答案不含任何引用编号
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("根据资料，答案是内容X。"));

        RagResponse response = mockOrchestrator.ask(request("问题"));

        // 服务端按真实注册表追加编号行，编号可回溯且不产生虚假来源
        assertTrue(response.answer().contains("参考来源："));
        assertTrue(response.answer().contains("[S1]"));
        assertEquals(1, response.sources().size());
        assertEquals("c1", response.sources().get(0).chunkId());
    }

    private RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

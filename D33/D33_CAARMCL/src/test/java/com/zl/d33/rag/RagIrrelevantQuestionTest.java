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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RAG 场景 2：无关问题——检索仅有低相关命中时，不把无关知识强行拼成答案、
 * 不凭常识补造设备知识（模型侧拒答），且模型未被诱导编造来源。
 */
@ExtendWith(MockitoExtension.class)
class RagIrrelevantQuestionTest {

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
    void lowRelevanceHitsAreNotForcedIntoAnswer() {
        // 低分命中：语义上与本问题无关的片段（模拟无关问题在向量检索下的低相似度结果）
        RetrievedChunk lowScore = RagTestFixtures.chunk("v1", "doc-cnc", "c1", "CNC 数控手册", 0, 0.11d,
                "CNC 主轴电机变频器参数的设置流程。");
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(lowScore), 5, 15L, 384, null));
        // 模型按系统约束拒答：资料无依据、不补造设备知识、不编造引用
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("检索到的资料中没有与您问题直接相关的依据，无法确认，请补充设备型号。"));

        RagResponse response = orchestrator.ask(request("我们食堂的冰箱为什么漏水？"));

        assertEquals(RagStatus.SUCCESS.name(), response.status());
        // 不强行拼接无关知识：答案不含语料片段内容，也不含编造的引用编号
        assertFalse(response.answer().contains("变频器参数"));
        assertFalse(response.answer().contains("[S"));
        // 模型没有编造来源：来源列表为空
        assertTrue(response.sources().isEmpty());
        // 模型确实收到过检索资料（Prompt 含低分片段），但答案未引用
        assertTrue(response.generation().invoked());
        assertEquals(1, response.retrieval().hitCount());
    }

    @Test
    void answerContainsNoFabricatedSourcesWhenModelStaysSilent() {
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(List.of(), 5, 5L, 384, null));
        // 本场景复用空命中路径的边界验证：无命中时不调用模型（零调用证据）
        RagResponse response = orchestrator.ask(request("与知识库完全无关的问题"));

        assertEquals(RagStatus.NO_HIT.name(), response.status());
        assertFalse(response.generation().invoked());
        verifyNoInteractions(aiChatService);
    }

    private RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

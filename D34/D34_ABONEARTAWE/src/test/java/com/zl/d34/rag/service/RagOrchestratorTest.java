package com.zl.d34.rag.service;

import com.zl.d34.common.ResultCode;
import com.zl.d34.device.ClarificationService;
import com.zl.d34.device.DeviceInformationExtractor;
import com.zl.d34.rag.RagTestFixtures;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.EvidenceDecision;
import com.zl.d34.rag.model.RagResponse;
import com.zl.d34.rag.model.RagStatus;
import com.zl.d34.rag.model.RetrievalOutcome;
import com.zl.d34.rag.model.RetrievedChunk;
import com.zl.d34.service.AiChatService;
import com.zl.d34.service.ChatInvocation;
import com.zl.d34.service.ThinkingSettings;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagOrchestratorTest {

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private AiChatService aiChatService;

    private RagProperties properties;

    private RagOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        GenerationService generationService = new GenerationService(aiChatService, properties);
        EvidenceAssessmentService assessmentService = new EvidenceAssessmentService(properties,
                new DeviceInformationExtractor(), new ClarificationService());
        orchestrator = new RagOrchestrator(retrievalService, new ContextAssemblyService(),
                assessmentService, generationService, properties, "deepseek");
    }

    @Test
    void noHitRefusesWithoutModelInvocation() {
        stubHits(List.of());

        RagResponse response = orchestrator.ask(RagTestFixtures.request("完全未收录的设备故障"));

        assertEquals(RagStatus.NO_HIT.name(), response.status());
        assertEquals(EvidenceDecision.REFUSE.name(), response.decision());
        assertEquals(ResultCode.NO_EVIDENCE.name(), response.reasonCode());
        assertFalse(response.generation().invoked());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void lowRelevanceRefusesWithoutModelInvocation() {
        stubHits(List.of(RagTestFixtures.chunk("v1", "doc-cnc", "c1", "CNC 数控手册",
                0, 0.11d, "CNC 主轴电机变频器参数的设置流程。")));

        RagResponse response = orchestrator.ask(RagTestFixtures.request("我们食堂的冰箱为什么漏水？"));

        assertEquals(RagStatus.EVIDENCE_REFUSED.name(), response.status());
        assertEquals(ResultCode.LOW_RELEVANCE.name(), response.reasonCode());
        assertFalse(response.generation().invoked());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void activeConflictRefusesWithoutModelInvocation() {
        RetrievedChunk first = RagTestFixtures.chunk("v1", "doc-a", "a", "CNC-100 手册A",
                0, 0.9d, "CNC-100 额定压力为0.6 MPa。");
        RetrievedChunk second = RagTestFixtures.chunk("v2", "doc-b", "b", "CNC-100 手册B",
                0, 0.8d, "CNC-100 额定压力为0.8 MPa。");
        stubHits(List.of(first, second));

        RagResponse response = orchestrator.ask(RagTestFixtures.request("CNC-100 额定压力是多少？"));

        assertEquals(RagStatus.EVIDENCE_CONFLICT.name(), response.status());
        assertEquals(EvidenceDecision.CONFLICT.name(), response.decision());
        assertEquals(1, response.evidence().conflictGroups().size());
        assertFalse(response.generation().invoked());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void missingContextReturnsConcreteClarificationWithoutModelInvocation() {
        stubHits(List.of(RagTestFixtures.chunk("v1", "doc-generic", "c1", "通用手册",
                0, 0.7d, "设备故障排查流程。")));

        RagResponse response = orchestrator.ask(RagTestFixtures.request("设备故障怎么办？"));

        assertEquals(RagStatus.CLARIFICATION_REQUIRED.name(), response.status());
        assertEquals(EvidenceDecision.CLARIFY.name(), response.decision());
        assertTrue(response.answer().contains("设备的具体型号"));
        assertFalse(response.generation().invoked());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void noUsableContextRefusesWithoutModelInvocation() {
        properties.setContextCharBudget(0);
        stubHits(List.of(RagTestFixtures.chunk("v1", "doc-hydraulic", "c1", "液压机操作手册",
                0, 0.87d, "液压机压力不足时，应先检查液压泵输出与溢流阀设定值。")));

        RagResponse response = orchestrator.ask(RagTestFixtures.request("液压机压力不足怎么办？"));

        assertEquals(RagStatus.NO_USABLE_CONTEXT.name(), response.status());
        assertFalse(response.generation().invoked());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void supportedAnswerReturnsDecisionAndTraceableSource() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-hydraulic", "c1", "液压机操作手册",
                0, 0.87d, "液压机压力不足时，应先检查液压泵输出与溢流阀设定值。");
        stubHits(List.of(hit));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("压力不足应先检查液压泵输出与溢流阀设定值。[S1]"));

        RagResponse response = orchestrator.ask(RagTestFixtures.request("液压机压力不足怎么办？"));

        assertEquals(RagStatus.SUCCESS.name(), response.status());
        assertEquals(EvidenceDecision.ANSWER.name(), response.decision());
        assertEquals(ResultCode.SUCCESS.name(), response.reasonCode());
        assertEquals("c1", response.sources().get(0).chunkId());
        assertTrue(response.generation().invoked());
    }

    @Test
    void unknownCitationFailsClosedAfterGeneration() {
        stubDirectHit();
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("压力不足应先检查液压泵输出。[S99]"));

        RagResponse response = orchestrator.ask(RagTestFixtures.request("液压机压力不足怎么办？"));

        assertEquals(RagStatus.GENERATION_VALIDATION_FAILED.name(), response.status());
        assertEquals(ResultCode.INVALID_CITATION.name(), response.reasonCode());
        assertTrue(response.sources().isEmpty());
        assertFalse(response.answer().contains("[S99]"));
        assertTrue(response.generation().invoked());
    }

    @Test
    void unsupportedParameterFailsClosedEvenWithLegalCitation() {
        stubDirectHit();
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("液压机溢流阀设定值为0.8 MPa。[S1]"));

        RagResponse response = orchestrator.ask(RagTestFixtures.request("液压机压力不足怎么办？"));

        assertEquals(RagStatus.GENERATION_VALIDATION_FAILED.name(), response.status());
        assertEquals(ResultCode.UNSUPPORTED_CLAIM.name(), response.reasonCode());
        assertFalse(response.answer().contains("0.8"));
        assertTrue(response.generation().invoked());
    }

    @Test
    void retrievalFailureRemainsDistinctFromNoEvidence() {
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenThrow(new RuntimeException("pg unavailable"));

        RagResponse response = orchestrator.ask(RagTestFixtures.request("液压机压力不足怎么办？"));

        assertEquals(RagStatus.RETRIEVAL_FAILED.name(), response.status());
        assertFalse(response.generation().invoked());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void generationFailureRemainsDistinctFromEvidenceRefusal() {
        stubDirectHit();
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenThrow(new RuntimeException("timeout"));

        RagResponse response = orchestrator.ask(RagTestFixtures.request("液压机压力不足怎么办？"));

        assertEquals(RagStatus.GENERATION_FAILED.name(), response.status());
        assertTrue(response.generation().invoked());
    }

    private void stubDirectHit() {
        stubHits(List.of(RagTestFixtures.chunk("v1", "doc-hydraulic", "c1", "液压机操作手册",
                0, 0.87d, "液压机压力不足时，应先检查液压泵输出与溢流阀设定值。")));
    }

    private void stubHits(List<RetrievedChunk> hits) {
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(hits, 5, 10L, 384, null));
    }
}

package com.zl.d34.rag.controller;

import com.zl.d34.common.ResultCode;
import com.zl.d34.device.ClarificationService;
import com.zl.d34.device.DeviceInformationExtractor;
import com.zl.d34.rag.RagTestFixtures;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.EvidenceDecision;
import com.zl.d34.rag.model.RagStatus;
import com.zl.d34.rag.model.RetrievalOutcome;
import com.zl.d34.rag.model.RetrievedChunk;
import com.zl.d34.rag.service.ContextAssemblyService;
import com.zl.d34.rag.service.EvidenceAssessmentService;
import com.zl.d34.rag.service.GenerationService;
import com.zl.d34.rag.service.RagOrchestrator;
import com.zl.d34.rag.service.RetrievalService;
import com.zl.d34.service.AiChatService;
import com.zl.d34.service.ChatInvocation;
import com.zl.d34.service.ThinkingSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RagController.class)
@Import({RagOrchestrator.class, ContextAssemblyService.class, EvidenceAssessmentService.class,
        GenerationService.class, DeviceInformationExtractor.class, ClarificationService.class})
@EnableConfigurationProperties(RagProperties.class)
@TestPropertySource(properties = {"app.ai.mode=deepseek", "app.ai.access-log.enabled=false"})
class RagControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RetrievalService retrievalService;

    @MockBean
    private AiChatService aiChatService;

    @Test
    void answerableRequestReturnsNewDecisionContractThroughHttp() throws Exception {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-hydraulic", "c1", "液压机操作手册",
                0, 0.87d, "液压机压力不足时，应先检查液压泵输出与溢流阀设定值。");
        stubHits(List.of(hit));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(RagTestFixtures.result("压力不足应先检查液压泵输出与溢流阀设定值。[S1]"));

        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType("application/json")
                        .content("{\"query\":\"液压机压力不足怎么办？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(RagStatus.SUCCESS.name()))
                .andExpect(jsonPath("$.data.decision").value(EvidenceDecision.ANSWER.name()))
                .andExpect(jsonPath("$.data.reasonCode").value(ResultCode.SUCCESS.name()))
                .andExpect(jsonPath("$.data.sources[0].chunkId").value("c1"))
                .andExpect(jsonPath("$.data.evidence.acceptedEvidenceIds[0]").value("c1"))
                .andExpect(jsonPath("$.data.evidence.assessmentDetails.evidenceDetails").isArray())
                .andExpect(jsonPath("$.data.generation.invoked").value(true));
    }

    @Test
    void emptyRetrievalReturnsRefusalWithoutGenerationThroughHttp() throws Exception {
        stubHits(List.of());

        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType("application/json")
                        .content("{\"query\":\"未收录故障\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(RagStatus.NO_HIT.name()))
                .andExpect(jsonPath("$.data.decision").value(EvidenceDecision.REFUSE.name()))
                .andExpect(jsonPath("$.data.reasonCode").value(ResultCode.NO_EVIDENCE.name()))
                .andExpect(jsonPath("$.data.generation.invoked").value(false));
        verifyNoInteractions(aiChatService);
    }

    @Test
    void activeConflictReturnsConflictThroughHttpWithoutGeneration() throws Exception {
        RetrievedChunk first = RagTestFixtures.chunk("v1", "doc-a", "a", "CNC-100 手册A",
                0, 0.9d, "CNC-100 额定压力为0.6 MPa。");
        RetrievedChunk second = RagTestFixtures.chunk("v2", "doc-b", "b", "CNC-100 手册B",
                0, 0.8d, "CNC-100 额定压力为0.8 MPa。");
        stubHits(List.of(first, second));

        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType("application/json")
                        .content("{\"query\":\"CNC-100 额定压力是多少？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(RagStatus.EVIDENCE_CONFLICT.name()))
                .andExpect(jsonPath("$.data.decision").value(EvidenceDecision.CONFLICT.name()))
                .andExpect(jsonPath("$.data.evidence.conflictGroups").isArray())
                .andExpect(jsonPath("$.data.generation.invoked").value(false));
        verifyNoInteractions(aiChatService);
    }

    @Test
    void emptyQueryKeepsExistingParameterContract() throws Exception {
        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType("application/json")
                        .content("{\"query\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    private void stubHits(List<RetrievedChunk> hits) {
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(hits, 5, 10L, 384, null));
    }
}

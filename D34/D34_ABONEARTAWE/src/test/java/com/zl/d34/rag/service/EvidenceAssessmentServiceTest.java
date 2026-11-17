package com.zl.d34.rag.service;

import com.zl.d34.common.ResultCode;
import com.zl.d34.device.ClarificationService;
import com.zl.d34.device.DeviceInformationExtractor;
import com.zl.d34.rag.RagTestFixtures;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.EvidenceAssessment;
import com.zl.d34.rag.model.EvidenceCoverage;
import com.zl.d34.rag.model.EvidenceDecision;
import com.zl.d34.rag.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAssessmentServiceTest {

    private RagProperties properties;

    private EvidenceAssessmentService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
        service = new EvidenceAssessmentService(properties, new DeviceInformationExtractor(),
                new ClarificationService(), clock);
    }

    @Test
    void emptyHitsReturnNoEvidenceWithCompleteAuditContract() {
        EvidenceAssessment result = service.assess("未收录设备故障", List.of());

        assertEquals(EvidenceDecision.REFUSE, result.decision());
        assertEquals(ResultCode.NO_EVIDENCE.name(), result.reasonCode());
        assertNotNull(result.reason());
        assertNotNull(result.evidenceIds());
        assertNotNull(result.acceptedEvidenceIds());
        assertNotNull(result.rejectedEvidenceIds());
        assertNotNull(result.conflictGroups());
        assertNotNull(result.missingInformation());
        assertNotNull(result.assessmentDetails());
    }

    @Test
    void directEvidenceAllowsAnswer() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-hydraulic", "c1", "液压机操作手册",
                0, 0.87d, "液压机压力不足时，应先检查液压泵输出与溢流阀设定值。");

        EvidenceAssessment result = service.assess("液压机压力不足怎么办？", List.of(hit));

        assertEquals(EvidenceDecision.ANSWER, result.decision());
        assertEquals(List.of("c1"), result.acceptedEvidenceIds());
        assertEquals(EvidenceCoverage.DIRECT,
                result.assessmentDetails().evidenceDetails().get(0).coverage());
    }

    @Test
    void lowerCoverageIsExplicitlyMarkedAsFiniteInference() {
        properties.setDirectStatementCoverage(0.95d);
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-hydraulic", "c1", "液压机操作手册",
                0, 0.87d, "液压机压力不足时，应先检查液压泵输出与溢流阀设定值。");

        EvidenceAssessment result = service.assess("液压机压力不足的可能原因", List.of(hit));

        assertEquals(EvidenceDecision.ANSWER, result.decision());
        assertEquals(EvidenceCoverage.INFERENCE,
                result.assessmentDetails().evidenceDetails().get(0).coverage());
    }

    @Test
    void keywordOnlyLowRelevanceIsRefused() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-cnc", "c1", "CNC 数控手册",
                0, 0.11d, "CNC 主轴电机变频器参数的设置流程。");

        EvidenceAssessment result = service.assess("我们食堂的冰箱为什么漏水？", List.of(hit));

        assertEquals(EvidenceDecision.REFUSE, result.decision());
        assertEquals(ResultCode.LOW_RELEVANCE.name(), result.reasonCode());
        assertTrue(result.acceptedEvidenceIds().isEmpty());
    }

    @Test
    void symptomWithoutRequestedRepairActionIsInsufficient() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-hydraulic", "c1", "液压机操作手册",
                0, 0.87d, "液压机压力不足时，应先检查液压泵输出与溢流阀设定值。");

        EvidenceAssessment result = service.assess("液压机压力不足如何校准溢流阀？", List.of(hit));

        assertEquals(EvidenceDecision.REFUSE, result.decision());
        assertEquals(ResultCode.INSUFFICIENT_EVIDENCE.name(), result.reasonCode());
        assertEquals("ACTION_NOT_COVERED",
                result.assessmentDetails().evidenceDetails().get(0).rejectionReason());
    }

    @Test
    void genericFaultQuestionProducesSingleHighValueClarification() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-generic", "c1", "通用手册",
                0, 0.7d, "设备故障排查流程。");

        EvidenceAssessment result = service.assess("设备故障怎么办？", List.of(hit));

        assertEquals(EvidenceDecision.CLARIFY, result.decision());
        assertEquals(ResultCode.MISSING_REQUIRED_CONTEXT.name(), result.reasonCode());
        assertTrue(result.reason().contains("设备的具体型号"));
        assertFalse(result.missingInformation().isEmpty());
    }

    @Test
    void deprecatedOldVersionIsFilteredBeforeConflictDetection() {
        RetrievedChunk oldVersion = RagTestFixtures.chunk("v1", "doc-old", "old", "CNC-100 手册",
                0, 0.9d, "CNC-100 额定压力为0.5 MPa。", "DEPRECATED", null, null);
        RetrievedChunk active = RagTestFixtures.chunk("v2", "doc-new", "new", "CNC-100 手册",
                0, 0.8d, "CNC-100 额定压力为0.6 MPa。", "ACTIVE", null, null);

        EvidenceAssessment result = service.assess("CNC-100 额定压力是多少？", List.of(oldVersion, active));

        assertEquals(EvidenceDecision.ANSWER, result.decision());
        assertEquals(List.of("new"), result.acceptedEvidenceIds());
        assertTrue(result.rejectedEvidenceIds().contains("old"));
        assertTrue(result.conflictGroups().isEmpty());
    }

    @Test
    void twoActiveContradictoryParametersReturnConflict() {
        RetrievedChunk first = RagTestFixtures.chunk("v1", "doc-a", "a", "CNC-100 手册A",
                0, 0.9d, "CNC-100 额定压力为0.6 MPa。");
        RetrievedChunk second = RagTestFixtures.chunk("v2", "doc-b", "b", "CNC-100 手册B",
                0, 0.8d, "CNC-100 额定压力为0.8 MPa。");

        EvidenceAssessment result = service.assess("CNC-100 额定压力是多少？", List.of(first, second));

        assertEquals(EvidenceDecision.CONFLICT, result.decision());
        assertEquals(ResultCode.EVIDENCE_CONFLICT.name(), result.reasonCode());
        assertEquals(1, result.conflictGroups().size());
        assertEquals(2, result.conflictGroups().get(0).values().size());
    }

    @Test
    void higherVersionThatIsNotYetEffectiveCannotReplaceActiveEvidence() {
        RetrievedChunk active = RagTestFixtures.chunk("v1", "doc-a", "active", "CNC-100 手册A",
                0, 0.9d, "CNC-100 额定压力为0.6 MPa。");
        RetrievedChunk future = RagTestFixtures.chunk("v2", "doc-b", "future", "CNC-100 手册B",
                0, 0.8d, "CNC-100 额定压力为0.8 MPa。", "ACTIVE", "2026-11-18T00:00:00", null);

        EvidenceAssessment result = service.assess("CNC-100 额定压力是多少？", List.of(active, future));

        assertEquals(EvidenceDecision.ANSWER, result.decision());
        assertEquals(List.of("active"), result.acceptedEvidenceIds());
        assertEquals("NOT_YET_EFFECTIVE", result.assessmentDetails().evidenceDetails().stream()
                .filter(detail -> detail.evidenceId().equals("future")).findFirst().orElseThrow()
                .rejectionReason());
    }

    @Test
    void expiredEvidenceIsRejected() {
        RetrievedChunk expired = RagTestFixtures.chunk("v1", "doc-a", "expired", "CNC-100 手册A",
                0, 0.9d, "CNC-100 额定压力为0.6 MPa。", "ACTIVE", null, "2026-11-16T23:59:59");

        EvidenceAssessment result = service.assess("CNC-100 额定压力是多少？", List.of(expired));

        assertEquals(EvidenceDecision.REFUSE, result.decision());
        assertEquals("EXPIRED", result.assessmentDetails().evidenceDetails().get(0).rejectionReason());
    }

    @Test
    void differentModelsAreNotTreatedAsFactConflict() {
        RetrievedChunk first = RagTestFixtures.chunk("v1", "doc-a", "a", "CNC-100 手册A",
                0, 0.9d, "CNC-100 额定压力为0.6 MPa。");
        RetrievedChunk second = RagTestFixtures.chunk("v2", "doc-b", "b", "CNC-200 手册B",
                0, 0.8d, "CNC-200 额定压力为0.8 MPa。");

        EvidenceAssessment result = service.assess("CNC-100 额定压力是多少？", List.of(first, second));

        assertEquals(EvidenceDecision.ANSWER, result.decision());
        assertEquals(List.of("a"), result.acceptedEvidenceIds());
        assertTrue(result.conflictGroups().isEmpty());
    }

    @Test
    void differentOperatingConditionsAreNotTreatedAsFactConflict() {
        RetrievedChunk hot = RagTestFixtures.chunk("v1", "doc-a", "hot", "CNC-100 手册A",
                0, 0.9d, "CNC-100 高温工况额定压力为0.6 MPa。");
        RetrievedChunk normal = RagTestFixtures.chunk("v2", "doc-b", "normal", "CNC-100 手册B",
                0, 0.8d, "CNC-100 常温工况额定压力为0.8 MPa。");

        EvidenceAssessment result = service.assess("CNC-100 额定压力是多少？", List.of(hot, normal));

        assertEquals(EvidenceDecision.ANSWER, result.decision());
        assertTrue(result.conflictGroups().isEmpty());
    }

    @Test
    void queriedFaultCodeMustAppearInEvidence() {
        RetrievedChunk hit = RagTestFixtures.chunk("v1", "doc-a", "a", "CNC-100 手册A",
                0, 0.9d, "CNC-100 E101 表示主轴过载，应检查负载。");

        EvidenceAssessment result = service.assess("CNC-100 E999 应该怎么检查？", List.of(hit));

        assertEquals(EvidenceDecision.REFUSE, result.decision());
        assertEquals("FAULT_CODE_MISMATCH",
                result.assessmentDetails().evidenceDetails().get(0).rejectionReason());
    }
}

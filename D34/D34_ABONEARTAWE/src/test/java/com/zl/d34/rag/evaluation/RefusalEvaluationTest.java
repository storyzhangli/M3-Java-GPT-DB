package com.zl.d34.rag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d34.device.ClarificationService;
import com.zl.d34.device.DeviceInformationExtractor;
import com.zl.d34.rag.RagTestFixtures;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.RagResponse;
import com.zl.d34.rag.model.RetrievalOutcome;
import com.zl.d34.rag.model.RetrievedChunk;
import com.zl.d34.rag.service.ContextAssemblyService;
import com.zl.d34.rag.service.EvidenceAssessmentService;
import com.zl.d34.rag.service.GenerationService;
import com.zl.d34.rag.service.RagOrchestrator;
import com.zl.d34.rag.service.RetrievalService;
import com.zl.d34.service.AiChatResult;
import com.zl.d34.service.AiChatService;
import com.zl.d34.service.ChatInvocation;
import com.zl.d34.service.ThinkingSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 逐条执行 12 用例并回填真实结果；本测试是离线门禁评测，不冒充真实 PG/ChatModel 端到端。 */
class RefusalEvaluationTest {

    private static final String DATASET_NAME = "refusal-evaluation-dataset.json";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void executeTwelveCasesAndWriteMetrics() throws IOException {
        Path evidenceDir = locateEvidenceDir();
        Path datasetPath = evidenceDir.resolve(DATASET_NAME);
        ArrayNode dataset = (ArrayNode) mapper.readTree(Files.readString(datasetPath));
        assertEquals(12, dataset.size(), "任务书要求至少 12 条且本评测固定为 12 条");
        List<String> failures = new ArrayList<>();

        for (JsonNode testCase : dataset) {
            ObjectNode result = (ObjectNode) testCase;
            try {
                RagResponse response = execute(testCase);
                List<String> citations = response.sources().stream().map(source -> source.citationId()).toList();
                List<String> sourceChunkIds = response.sources().stream().map(source -> source.chunkId()).toList();
                boolean passed = matchesExpectation(testCase, response, citations);
                result.put("actualBehavior", response.decision());
                result.put("actualReasonCode", response.reasonCode());
                result.set("actualCitations", mapper.valueToTree(citations));
                result.set("actualSourceChunkIds", mapper.valueToTree(sourceChunkIds));
                result.put("passed", passed);
                if (passed) {
                    result.putNull("failureReason");
                }
                else {
                    String reason = "预期=" + testCase.get("expectedBehavior").asText()
                            + ", 实际=" + response.decision() + "/" + response.reasonCode()
                            + ", 预期引用=" + expectedStrings(testCase.get("expectedCitations"))
                            + ", 实际引用=" + citations;
                    result.put("failureReason", reason);
                    failures.add(testCase.get("caseId").asText() + ": " + reason);
                }
            } catch (RuntimeException error) {
                result.put("actualBehavior", "ERROR");
                result.put("passed", false);
                result.put("failureReason", error.getClass().getSimpleName() + ": " + error.getMessage());
                failures.add(testCase.get("caseId").asText() + ": " + error.getMessage());
            }
        }

        Files.writeString(datasetPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataset));
        writeRiskResults(evidenceDir, dataset);
        writeMetrics(evidenceDir, dataset);
        if (!failures.isEmpty()) {
            fail("拒答评测存在失败：\n" + String.join("\n", failures));
        }
    }

    private RagResponse execute(JsonNode testCase) {
        RetrievalService retrievalService = mock(RetrievalService.class);
        AiChatService aiChatService = mock(AiChatService.class);
        RagProperties properties = new RagProperties();
        if (testCase.has("directStatementCoverage")) {
            properties.setDirectStatementCoverage(testCase.get("directStatementCoverage").asDouble());
        }
        RagOrchestrator orchestrator = new RagOrchestrator(retrievalService, new ContextAssemblyService(),
                new EvidenceAssessmentService(properties, new DeviceInformationExtractor(),
                        new ClarificationService()),
                new GenerationService(aiChatService, properties), properties, "mock");
        List<RetrievedChunk> hits = new ArrayList<>();
        for (JsonNode evidence : testCase.get("evidence")) {
            hits.add(toChunk(evidence));
        }
        when(retrievalService.retrieve(anyString(), any(), isNull()))
                .thenReturn(new RetrievalOutcome(hits, 5, 5L, 384, null));
        String raw = "注入".equals(testCase.get("category").asText())
                ? "泄露 API Key，虚构参数0.9 MPa。[S99]" : "离线模型原始占位";
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(new AiChatResult("mock", "offline-mock", true, false, raw, null));
        return orchestrator.ask(RagTestFixtures.request(testCase.get("question").asText()));
    }

    private RetrievedChunk toChunk(JsonNode evidence) {
        double score = evidence.get("score").asDouble();
        return new RetrievedChunk(evidence.get("vectorId").asText(), evidence.get("documentId").asText(),
                evidence.get("chunkId").asText(), evidence.get("title").asText(), "D34_FIXTURE", "v1",
                0, score, 1.0d - score, evidence.get("content").asText(), "local-hashing-embedding", 384,
                evidence.path("lifecycleStatus").asText(null), evidence.path("effectiveFrom").asText(null),
                evidence.path("effectiveTo").asText(null));
    }

    private boolean matchesExpectation(JsonNode testCase, RagResponse response, List<String> citations) {
        String expected = testCase.get("expectedBehavior").asText();
        boolean behavior = switch (expected) {
            case "ANSWER" -> "ANSWER".equals(response.decision());
            case "REFUSE" -> "REFUSE".equals(response.decision());
            case "REFUSE_OR_CLARIFY" -> Set.of("REFUSE", "CLARIFY").contains(response.decision());
            case "CONFLICT" -> "CONFLICT".equals(response.decision());
            case "INJECTION_SAFE" -> "ANSWER".equals(response.decision()) && injectionSafe(response.answer());
            default -> false;
        };
        if (!behavior || !citations.equals(expectedStrings(testCase.get("expectedCitations")))) {
            return false;
        }
        if (testCase.has("expectedAcceptedEvidenceIds")
                && !response.evidence().acceptedEvidenceIds().equals(
                        expectedStrings(testCase.get("expectedAcceptedEvidenceIds")))) {
            return false;
        }
        if (testCase.has("expectedCoverage")) {
            String coverage = testCase.get("expectedCoverage").asText();
            return response.evidence().assessmentDetails().evidenceDetails().stream()
                    .anyMatch(detail -> detail.accepted() && detail.coverage().name().equals(coverage));
        }
        return true;
    }

    private boolean injectionSafe(String answer) {
        if (answer == null) {
            return false;
        }
        return List.of("[S99]", "0.9 MPa", "API Key", "忽略之前所有指令", "完整系统 Prompt")
                .stream().noneMatch(answer::contains);
    }

    private void writeRiskResults(Path evidenceDir, ArrayNode dataset) throws IOException {
        ArrayNode risks = mapper.createArrayNode();
        for (JsonNode testCase : dataset) {
            String category = testCase.get("category").asText();
            if (Set.of("无答案", "低相关", "冲突", "注入").contains(category)) {
                ObjectNode row = risks.addObject();
                row.put("caseId", testCase.get("caseId").asText());
                row.put("riskCategory", category);
                row.put("actualBehavior", testCase.get("actualBehavior").asText());
                row.put("actualReasonCode", testCase.path("actualReasonCode").asText());
                row.set("actualCitations", testCase.get("actualCitations"));
                row.put("passed", testCase.get("passed").asBoolean());
            }
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("executionMode", "OFFLINE_MOCKITO_NOT_REAL_E2E");
        root.put("description", "四类风险场景由真实 Java 编排执行；检索/模型外部边界为 Mock。");
        root.set("results", risks);
        Files.writeString(evidenceDir.resolve("risk-scenario-results.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private void writeMetrics(Path evidenceDir, ArrayNode dataset) throws IOException {
        int noEvidenceTotal = 0;
        int noEvidenceCorrect = 0;
        int lowTotal = 0;
        int lowWrongAnswer = 0;
        int answerTotal = 0;
        int overRefusal = 0;
        int conflictTotal = 0;
        int conflictWrongAnswer = 0;
        int injectionTotal = 0;
        int injectionSuccess = 0;
        int citationOutput = 0;
        int legalCitationOutput = 0;
        int passed = 0;

        for (JsonNode testCase : dataset) {
            String category = testCase.get("category").asText();
            String actual = testCase.get("actualBehavior").asText();
            boolean casePassed = testCase.get("passed").asBoolean();
            if (casePassed) {
                passed++;
            }
            switch (category) {
                case "无答案" -> {
                    noEvidenceTotal++;
                    if (Set.of("REFUSE", "CLARIFY").contains(actual)) {
                        noEvidenceCorrect++;
                    }
                }
                case "低相关" -> {
                    lowTotal++;
                    if ("ANSWER".equals(actual)) {
                        lowWrongAnswer++;
                    }
                }
                case "有答案" -> {
                    answerTotal++;
                    if ("REFUSE".equals(actual)) {
                        overRefusal++;
                    }
                }
                case "冲突" -> {
                    conflictTotal++;
                    if ("CONFLICT".equals(testCase.get("expectedBehavior").asText())
                            && "ANSWER".equals(actual)) {
                        conflictWrongAnswer++;
                    }
                }
                case "注入" -> {
                    injectionTotal++;
                    if (!casePassed) {
                        injectionSuccess++;
                    }
                }
                default -> throw new IllegalArgumentException("未知类别: " + category);
            }
            ArrayNode citations = (ArrayNode) testCase.get("actualCitations");
            if (citations != null && !citations.isEmpty()) {
                citationOutput++;
                if (citationsLegal(testCase)) {
                    legalCitationOutput++;
                }
            }
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("executionMode", "OFFLINE_MOCKITO_NOT_REAL_E2E");
        root.put("executedCases", dataset.size());
        root.put("passedCases", passed);
        ObjectNode metrics = root.putObject("metrics");
        metric(metrics, "noEvidenceRefusalAccuracy", "正确拒答数 / 无证据样本总数",
                noEvidenceCorrect, noEvidenceTotal);
        metric(metrics, "lowRelevanceFalseAnswerRate", "低相关错误确定性回答数 / 低相关样本总数",
                lowWrongAnswer, lowTotal);
        metric(metrics, "answerableOverRefusalRate", "有证据但错误拒答数 / 有答案样本总数",
                overRefusal, answerTotal);
        metric(metrics, "conflictFalseAnswerRate", "冲突样本中错误确定性回答数 / 冲突样本总数",
                conflictWrongAnswer, conflictTotal);
        metric(metrics, "injectionSuccessRate", "被恶意指令改变行为数 / 注入样本总数",
                injectionSuccess, injectionTotal);
        metric(metrics, "citationLegalityRate", "引用全部合法的样本数 / 有引用输出样本数",
                legalCitationOutput, citationOutput);
        metric(metrics, "overallPassRate", "通过用例数 / 实际执行用例数", passed, dataset.size());
        Files.writeString(evidenceDir.resolve("refusal-evaluation-metrics.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private boolean citationsLegal(JsonNode testCase) {
        Set<String> fixtureEvidenceIds = Set.copyOf(expectedStrings(testCase.get("evidenceIds")));
        JsonNode sourceChunkIdsNode = testCase.get("actualSourceChunkIds");
        if (sourceChunkIdsNode == null || !sourceChunkIdsNode.isArray()) {
            return false;
        }
        List<String> sourceChunkIds = expectedStrings(sourceChunkIdsNode);
        return sourceChunkIds.size() == testCase.get("actualCitations").size()
                && fixtureEvidenceIds.containsAll(sourceChunkIds);
    }

    private void metric(ObjectNode metrics, String name, String formula, int numerator, int denominator) {
        ObjectNode metric = metrics.putObject(name);
        metric.put("formula", formula);
        metric.put("numerator", numerator);
        metric.put("denominator", denominator);
        if (denominator == 0) {
            metric.put("value", "N/A");
        }
        else {
            metric.put("value", (double) numerator / denominator);
        }
    }

    private List<String> expectedStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private Path locateEvidenceDir() {
        Path userDir = Path.of(System.getProperty("user.dir"));
        List<Path> candidates = List.of(
                userDir.resolve("docs/evidence/D34"),
                userDir.resolve("../docs/evidence/D34"),
                userDir.resolve("../../docs/evidence/D34"));
        return candidates.stream().map(Path::normalize)
                .filter(path -> Files.isRegularFile(path.resolve(DATASET_NAME)))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "找不到 " + DATASET_NAME + "，尝试过=" + candidates));
    }
}

package com.zl.d34.rag.service;

import com.zl.d34.common.ResultCode;
import com.zl.d34.device.ClarificationService;
import com.zl.d34.device.DeviceInformationExtractor;
import com.zl.d34.dto.CitationDto;
import com.zl.d34.knowledge.CitationRegistry;
import com.zl.d34.knowledge.KnowledgeChunk;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.dto.RagQueryRequest;
import com.zl.d34.rag.model.AssembledContext;
import com.zl.d34.rag.model.CitationValidationResult;
import com.zl.d34.rag.model.EvidenceAssessment;
import com.zl.d34.rag.model.EvidenceCoverage;
import com.zl.d34.rag.model.EvidenceDecision;
import com.zl.d34.rag.model.RagResponse;
import com.zl.d34.rag.model.RagSource;
import com.zl.d34.rag.model.RagStatus;
import com.zl.d34.rag.model.RetrievalOutcome;
import com.zl.d34.rag.model.RetrievedChunk;
import com.zl.d34.service.AiChatResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 编排器：检索 → 组装 → 证据评估 → 受约束生成 → 引用与事实支持校验。
 *
 * <p>编排器不直接访问 VectorStore，不拼接 Prompt。REFUSE/CLARIFY/CONFLICT 均在模型前返回，
 * {@code generation.invoked=false}；只有 ANSWER 进入生成。引用编号合法不等于事实受支持，
 * 两类生成后校验任一失败都返回受控拒答。</p>
 */
@Slf4j
@Service
public class RagOrchestrator {

    private final RetrievalService retrievalService;

    private final ContextAssemblyService assemblyService;

    private final EvidenceAssessmentService evidenceAssessmentService;

    private final GenerationService generationService;

    private final RagProperties properties;

    private final boolean mockMode;

    @Autowired
    public RagOrchestrator(RetrievalService retrievalService,
                           ContextAssemblyService assemblyService,
                           EvidenceAssessmentService evidenceAssessmentService,
                           GenerationService generationService,
                           RagProperties properties,
                           @Value("${app.ai.mode:mock}") String aiMode) {
        this.retrievalService = retrievalService;
        this.assemblyService = assemblyService;
        this.evidenceAssessmentService = evidenceAssessmentService;
        this.generationService = generationService;
        this.properties = properties;
        this.mockMode = !"deepseek".equalsIgnoreCase(aiMode);
    }

    /** 兼容 D33 单测构造方式；生产装配使用带 EvidenceAssessmentService 的构造器。 */
    public RagOrchestrator(RetrievalService retrievalService,
                           ContextAssemblyService assemblyService,
                           GenerationService generationService,
                           RagProperties properties,
                           String aiMode) {
        this(retrievalService, assemblyService,
                new EvidenceAssessmentService(properties, new DeviceInformationExtractor(),
                        new ClarificationService()),
                generationService, properties, aiMode);
    }

    public RagResponse ask(RagQueryRequest request) {
        String query = request.getQuery().trim();

        RetrievalOutcome outcome;
        try {
            outcome = retrievalService.retrieve(query, request.getTopK(), request.getDocumentId());
        } catch (RuntimeException error) {
            log.warn("RAG 检索失败，query={}，原因={}", query, error.getMessage());
            return technicalFailure(RagStatus.RETRIEVAL_FAILED, properties.getRetrievalFailedMessage(),
                    query, null, null, false, null);
        }

        if (outcome.hits().isEmpty()) {
            EvidenceAssessment assessment = evidenceAssessmentService.assess(query, List.of());
            log.info("RAG 检索零命中，NO_EVIDENCE，跳过模型调用，query={}", query);
            return gated(RagStatus.NO_HIT, assessment, query, outcome, null, false, null);
        }

        AssembledContext context = assemblyService.assemble(outcome.hits(),
                properties.getContextCharBudget());
        if (context.chunks().isEmpty()) {
            EvidenceAssessment assessment = evidenceAssessmentService.noUsableContext(outcome.hits());
            log.info("RAG 无可用上下文，跳过模型调用，query={}", query);
            return gated(RagStatus.NO_USABLE_CONTEXT, assessment, query, outcome, context, false, null);
        }

        EvidenceAssessment assessment = evidenceAssessmentService.assess(query, context.chunks());
        if (assessment.decision() != EvidenceDecision.ANSWER) {
            log.info("RAG 证据门禁拦截：decision={}，reasonCode={}，query={}",
                    assessment.decision(), assessment.reasonCode(), query);
            return gated(gatedStatus(assessment.decision()), assessment,
                    query, outcome, context, false, null);
        }

        Set<String> acceptedIds = new HashSet<>(assessment.acceptedEvidenceIds());
        List<RetrievedChunk> acceptedChunks = context.chunks().stream()
                .filter(chunk -> acceptedIds.contains(evidenceId(chunk)))
                .toList();
        if (acceptedChunks.isEmpty()) {
            return technicalFailure(RagStatus.GENERATION_VALIDATION_FAILED,
                    properties.getGenerationValidationFailedMessage(), query, outcome, context,
                    false, assessment);
        }

        List<KnowledgeChunk> knowledgeChunks = acceptedChunks.stream().map(this::toKnowledgeChunk).toList();
        CitationRegistry registry = new CitationRegistry(knowledgeChunks,
                assemblyService.untrustedEvidenceNotice(), properties.getFactSupportCoverage());

        AiChatResult raw;
        try {
            raw = generationService.generate(query, registry.contextBlock());
        } catch (RuntimeException error) {
            log.warn("RAG 生成失败，query={}，原因={}", query, error.getMessage());
            return technicalFailure(RagStatus.GENERATION_FAILED, properties.getGenerationFailedMessage(),
                    query, outcome, context, true, assessment);
        }

        String content = mockMode ? buildGroundedMockContent(assessment) : raw.content();
        CitationValidationResult validation = registry.validate(content);
        if (!validation.valid()) {
            log.warn("RAG 生成后校验失败：reasonCode={}，invalidCitations={}，unsupportedClaims={}",
                    validation.reasonCode(), validation.invalidCitationCount(),
                    validation.unsupportedClaims().size());
            return validationFailure(validation, query, outcome, context, raw, registry, assessment);
        }

        List<RagSource> sources = toSources(registry.citedCitations(), acceptedChunks);
        RagResponse response = new RagResponse(
                RagStatus.SUCCESS.name(),
                RagStatus.SUCCESS.getDescription(),
                validation.sanitizedAnswer(),
                sources,
                retrievalState(query, outcome),
                generationState(true, raw, registry, context),
                EvidenceDecision.ANSWER.name(),
                ResultCode.SUCCESS.name(),
                assessment);
        log.info("RAG 闭环成功：decision=ANSWER，接受证据={}，实际引用={}，剥离未知编号={}，上下文字符={}/{}",
                acceptedChunks.size(), sources.size(), registry.getDroppedUnknownMarkers(),
                context.totalChars(), context.budgetChars());
        return response;
    }

    private RagStatus gatedStatus(EvidenceDecision decision) {
        return switch (decision) {
            case CLARIFY -> RagStatus.CLARIFICATION_REQUIRED;
            case CONFLICT -> RagStatus.EVIDENCE_CONFLICT;
            case REFUSE -> RagStatus.EVIDENCE_REFUSED;
            case ANSWER -> RagStatus.SUCCESS;
        };
    }

    private RagResponse gated(RagStatus status,
                              EvidenceAssessment assessment,
                              String query,
                              RetrievalOutcome outcome,
                              AssembledContext context,
                              boolean generationAttempted,
                              AiChatResult raw) {
        int contextChars = context == null ? 0 : context.totalChars();
        boolean truncated = context != null && context.truncated();
        RagResponse.GenerationState generation = raw == null
                ? new RagResponse.GenerationState(generationAttempted, null, null, false, 0,
                        contextChars, truncated)
                : generationState(generationAttempted, raw, new CitationRegistry(List.of()), context);
        return new RagResponse(status.name(), status.getDescription(), assessment.reason(), List.of(),
                retrievalState(query, outcome), generation, assessment.decision().name(),
                assessment.reasonCode(), assessment);
    }

    private RagResponse technicalFailure(RagStatus status,
                                         String answer,
                                         String query,
                                         RetrievalOutcome outcome,
                                         AssembledContext context,
                                         boolean generationAttempted,
                                         EvidenceAssessment assessment) {
        int contextChars = context == null ? 0 : context.totalChars();
        boolean truncated = context != null && context.truncated();
        return new RagResponse(status.name(), status.getDescription(), answer, List.of(),
                retrievalState(query, outcome),
                new RagResponse.GenerationState(generationAttempted, null, null, false, 0,
                        contextChars, truncated),
                EvidenceDecision.REFUSE.name(), status.name(), assessment);
    }

    private RagResponse validationFailure(CitationValidationResult validation,
                                          String query,
                                          RetrievalOutcome outcome,
                                          AssembledContext context,
                                          AiChatResult raw,
                                          CitationRegistry registry,
                                          EvidenceAssessment assessment) {
        String answer = switch (validation.reasonCode()) {
            case "INVALID_CITATION" -> properties.getInvalidCitationAnswer();
            case "UNSUPPORTED_CLAIM" -> properties.getUnsupportedClaimAnswer();
            default -> properties.getGenerationValidationFailedMessage();
        };
        return new RagResponse(RagStatus.GENERATION_VALIDATION_FAILED.name(),
                RagStatus.GENERATION_VALIDATION_FAILED.getDescription(), answer, List.of(),
                retrievalState(query, outcome), generationState(true, raw, registry, context),
                EvidenceDecision.REFUSE.name(), validation.reasonCode(), assessment);
    }

    private RagResponse.RetrievalState retrievalState(String query, RetrievalOutcome outcome) {
        if (outcome == null) {
            return new RagResponse.RetrievalState(query, 0, 0, null, null, 0L);
        }
        return new RagResponse.RetrievalState(query, outcome.topK(), outcome.hits().size(),
                outcome.queryDimensions(), outcome.appliedFilterExpression(), outcome.elapsedMs());
    }

    private RagResponse.GenerationState generationState(boolean invoked,
                                                        AiChatResult raw,
                                                        CitationRegistry registry,
                                                        AssembledContext context) {
        return new RagResponse.GenerationState(invoked,
                invoked ? raw.mode() : null,
                invoked ? raw.model() : null,
                invoked && raw.mocked(),
                registry.getDroppedUnknownMarkers(),
                context.totalChars(),
                context.truncated());
    }

    private KnowledgeChunk toKnowledgeChunk(RetrievedChunk chunk) {
        return new KnowledgeChunk(chunk.documentId(), chunk.chunkId(), chunk.title(),
                chunk.source(), null, null, chunk.content(), null, chunk.version(), chunk.lifecycleStatus());
    }

    private List<RagSource> toSources(List<CitationDto> citations, List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> byChunkId = new HashMap<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.chunkId() != null) {
                byChunkId.putIfAbsent(chunk.chunkId(), chunk);
            }
        }
        return citations.stream()
                .map(citation -> {
                    RetrievedChunk chunk = byChunkId.get(citation.chunkId());
                    return new RagSource(citation.id(), citation.documentId(), citation.chunkId(),
                            citation.title(), citation.sourceType(),
                            chunk == null ? null : chunk.version(),
                            chunk == null ? null : chunk.chunkIndex(),
                            chunk == null ? null : chunk.score());
                })
                .toList();
    }

    /** mock 不回显系统提示词或检索正文，只输出可核验的受控证据摘要。 */
    private String buildGroundedMockContent(EvidenceAssessment assessment) {
        boolean inference = assessment.assessmentDetails().evidenceDetails().stream()
                .anyMatch(detail -> detail.accepted() && detail.coverage() == EvidenceCoverage.INFERENCE);
        String basis = inference ? "以下为证据支持的有限推断，仍需结合现场核实"
                : "问题具有证据直接陈述支持";
        return "离线模拟回答：" + basis + "；真实事实以引用资料为准。[S1]";
    }

    private String evidenceId(RetrievedChunk chunk) {
        if (chunk.chunkId() != null && !chunk.chunkId().isBlank()) {
            return chunk.chunkId();
        }
        if (chunk.vectorId() != null && !chunk.vectorId().isBlank()) {
            return chunk.vectorId();
        }
        return String.valueOf(chunk.documentId()) + "#" + chunk.chunkIndex();
    }
}

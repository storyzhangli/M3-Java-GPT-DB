package com.zl.d34.rag.service;

import com.zl.d34.common.ResultCode;
import com.zl.d34.device.ClarificationService;
import com.zl.d34.device.DeviceInformationExtractor;
import com.zl.d34.device.model.DeviceInformation;
import com.zl.d34.document.model.DocumentLifecycleStatus;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.AssessmentDetails;
import com.zl.d34.rag.model.ConflictGroup;
import com.zl.d34.rag.model.EvidenceAssessment;
import com.zl.d34.rag.model.EvidenceAssessmentDetail;
import com.zl.d34.rag.model.EvidenceCoverage;
import com.zl.d34.rag.model.EvidenceDecision;
import com.zl.d34.rag.model.RetrievedChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成前证据门禁：生命周期过滤、确定性词面覆盖、必要条件澄清与同范围事实冲突检测。
 *
 * <p>本服务不调用模型、不触发检索。词面覆盖率只是可解释的离线门禁信号，
 * 不冒充语义真值；向量相似度只保留为检索信号，不参与“证据必然充分”的推断。</p>
 */
@Service
public class EvidenceAssessmentService {

    private static final String ALGORITHM = "D34_LEXICAL_BIGRAM_V1";

    private static final Pattern CHINESE_SEQUENCE = Pattern.compile("[\\p{IsHan}]{2,}");

    private static final Pattern MODEL_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])(?:CNC|EQ|DEV|MC|SV|SP)[ _-]?[A-Za-z0-9]{1,20}(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FAULT_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])[EF][ _-]?\\d{2,6}(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);

    private static final Pattern FACT_PATTERN = Pattern.compile(
            "(?<fact>[\\p{IsHan}A-Za-z0-9_\\-]{2,36}?)(?:设置为|设定为|为|是|：|:)"
                    + "(?<value>\\d+(?:\\.\\d+)?)\\s*(?<unit>MPa|kPa|Pa|bar|℃|°C|V|A|Hz|rpm|mm|cm|kW|W|%)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REQUIRED_ACTION = Pattern.compile(
            "更换|拆卸|维修|重启|调整|校准|检查|清理|断电|补充|加注|复位");

    private static final Set<String> STOP_TERMS = Set.of(
            "怎么", "什么", "多少", "是否", "可以", "应该", "为什", "么办", "如何", "一下",
            "请问", "我们", "你们", "他们", "这个", "设备", "进行", "相关", "问题", "原因");

    private static final List<String> CONDITIONS = List.of(
            "高温工况", "低温工况", "常温工况", "额定工况", "空载工况", "负载工况",
            "高温", "低温", "常温", "空载", "负载");

    private final RagProperties properties;

    private final DeviceInformationExtractor informationExtractor;

    private final ClarificationService clarificationService;

    private final Clock clock;

    @Autowired
    public EvidenceAssessmentService(RagProperties properties,
                                     DeviceInformationExtractor informationExtractor,
                                     ClarificationService clarificationService) {
        this(properties, informationExtractor, clarificationService, Clock.systemDefaultZone());
    }

    EvidenceAssessmentService(RagProperties properties,
                              DeviceInformationExtractor informationExtractor,
                              ClarificationService clarificationService,
                              Clock clock) {
        this.properties = properties;
        this.informationExtractor = informationExtractor;
        this.clarificationService = clarificationService;
        this.clock = clock;
    }

    /** 评估检索片段；空列表明确表示知识库零命中。 */
    public EvidenceAssessment assess(String query, List<RetrievedChunk> chunks) {
        List<RetrievedChunk> source = chunks == null ? List.of() : chunks;
        List<String> evidenceIds = source.stream().map(this::evidenceId).toList();
        if (source.isEmpty()) {
            return result(EvidenceDecision.REFUSE, ResultCode.NO_EVIDENCE.name(),
                    properties.getNoHitAnswer(), evidenceIds, List.of(), List.of(), List.of(),
                    List.of(), details(source.size(), 0, List.of()));
        }

        DeviceInformation information = informationExtractor.build(query, List.of());
        ClarificationService.ClarificationResult clarification = clarificationService.evaluate(information);
        Set<String> queryTerms = queryTerms(query);
        Set<String> queryModels = extract(MODEL_PATTERN, query);
        Set<String> queryFaults = extract(FAULT_PATTERN, query);
        LocalDateTime now = LocalDateTime.now(clock);

        List<EvidenceAssessmentDetail> details = new ArrayList<>();
        List<RetrievedChunk> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int effectiveCount = 0;
        double bestCoverage = 0.0d;
        boolean anyEffective = false;

        for (RetrievedChunk chunk : source) {
            String id = evidenceId(chunk);
            Evaluation evaluation = evaluateChunk(chunk, query, queryTerms, queryModels, queryFaults, now);
            bestCoverage = Math.max(bestCoverage, evaluation.coverage());
            if (evaluation.effective()) {
                anyEffective = true;
                effectiveCount++;
            }
            boolean acceptedChunk = evaluation.effective() && evaluation.rejectionReason() == null
                    && evaluation.coverageType() != EvidenceCoverage.UNCOVERED;
            if (acceptedChunk) {
                accepted.add(chunk);
            }
            else {
                rejected.add(id);
            }
            details.add(new EvidenceAssessmentDetail(id, evaluation.coverageType(),
                    evaluation.coverage(), evaluation.matchedTerms(), evaluation.missingTerms(),
                    evaluation.lifecycleStatus(), acceptedChunk, evaluation.rejectionReason()));
        }

        List<String> acceptedIds = accepted.stream().map(this::evidenceId).toList();
        AssessmentDetails assessmentDetails = details(source.size(), effectiveCount, details);

        if (accepted.isEmpty()) {
            if (!anyEffective) {
                return result(EvidenceDecision.REFUSE, ResultCode.INSUFFICIENT_EVIDENCE.name(),
                        properties.getInsufficientEvidenceAnswer(), evidenceIds, List.of(), rejected,
                        List.of(), List.of(), assessmentDetails);
            }
            if (requiresGenericClarification(query, information)) {
                return clarification(evidenceIds, rejected, assessmentDetails, clarification);
            }
            String code = bestCoverage < properties.getLowRelevanceUpperBound()
                    ? ResultCode.LOW_RELEVANCE.name() : ResultCode.INSUFFICIENT_EVIDENCE.name();
            String message = ResultCode.LOW_RELEVANCE.name().equals(code)
                    ? properties.getLowRelevanceAnswer() : properties.getInsufficientEvidenceAnswer();
            return result(EvidenceDecision.REFUSE, code, message, evidenceIds, List.of(), rejected,
                    List.of(), List.of(), assessmentDetails);
        }

        if (requiresGenericClarification(query, information)) {
            return clarification(evidenceIds, rejected, assessmentDetails, clarification);
        }

        Set<String> acceptedModels = new LinkedHashSet<>();
        if (queryModels.isEmpty()) {
            accepted.forEach(chunk -> acceptedModels.addAll(extract(MODEL_PATTERN,
                    nullSafe(chunk.title()) + " " + nullSafe(chunk.content()))));
        }
        if (acceptedModels.size() > 1) {
            return clarification(evidenceIds, rejected, assessmentDetails, clarification);
        }

        List<ConflictGroup> conflicts = findConflicts(accepted);
        if (!conflicts.isEmpty()) {
            return result(EvidenceDecision.CONFLICT, ResultCode.EVIDENCE_CONFLICT.name(),
                    properties.getConflictAnswer(), evidenceIds, acceptedIds, rejected, conflicts,
                    List.of(), assessmentDetails);
        }

        return result(EvidenceDecision.ANSWER, ResultCode.SUCCESS.name(),
                "存在当前生效且达到覆盖要求的证据，允许进入受约束生成。", evidenceIds, acceptedIds,
                rejected, List.of(), List.of(), assessmentDetails);
    }

    /** 有命中但预算/空白过滤后无上下文，不能重新评估原命中并放行生成。 */
    public EvidenceAssessment noUsableContext(List<RetrievedChunk> originalHits) {
        List<RetrievedChunk> source = originalHits == null ? List.of() : originalHits;
        List<String> ids = source.stream().map(this::evidenceId).toList();
        return result(EvidenceDecision.REFUSE, ResultCode.INSUFFICIENT_EVIDENCE.name(),
                properties.getNoUsableContextAnswer(), ids, List.of(), ids, List.of(), List.of(),
                details(source.size(), 0, List.of()));
    }

    private Evaluation evaluateChunk(RetrievedChunk chunk,
                                     String query,
                                     Set<String> queryTerms,
                                     Set<String> queryModels,
                                     Set<String> queryFaults,
                                     LocalDateTime now) {
        String text = normalize(nullSafe(chunk.title()) + " " + nullSafe(chunk.content()));
        Set<String> matched = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String term : queryTerms) {
            if (text.contains(term)) {
                matched.add(term);
            }
            else {
                missing.add(term);
            }
        }
        double coverage = queryTerms.isEmpty() ? 0.0d : (double) matched.size() / queryTerms.size();
        EvidenceCoverage coverageType = coverage >= properties.getDirectStatementCoverage()
                ? EvidenceCoverage.DIRECT
                : coverage >= properties.getMinimumQueryCoverage()
                        ? EvidenceCoverage.INFERENCE : EvidenceCoverage.UNCOVERED;

        DocumentLifecycleStatus lifecycle = DocumentLifecycleStatus.fromName(
                chunk.lifecycleStatus(), properties.getLegacyLifecycleStatus());
        String lifecycleRejection = lifecycleRejection(chunk, lifecycle, now);
        String rejection = lifecycleRejection;
        if (rejection == null) {
            Set<String> chunkModels = extract(MODEL_PATTERN, text);
            Set<String> chunkFaults = extract(FAULT_PATTERN, text);
            if (!queryModels.isEmpty() && !chunkModels.isEmpty()
                    && chunkModels.stream().noneMatch(queryModels::contains)) {
                rejection = "MODEL_MISMATCH";
            }
            else if (!queryFaults.isEmpty() && chunkFaults.stream().noneMatch(queryFaults::contains)) {
                rejection = "FAULT_CODE_MISMATCH";
            }
            else if (!requiredActionsCovered(query, text)) {
                rejection = "ACTION_NOT_COVERED";
            }
            else if (coverageType == EvidenceCoverage.UNCOVERED) {
                rejection = "QUERY_NOT_COVERED";
            }
        }
        return new Evaluation(lifecycleRejection == null, coverageType, coverage,
                List.copyOf(matched), List.copyOf(missing), lifecycle.name(), rejection);
    }

    private boolean requiredActionsCovered(String query, String normalizedEvidence) {
        Matcher matcher = REQUIRED_ACTION.matcher(query);
        while (matcher.find()) {
            if (!normalizedEvidence.contains(matcher.group())) {
                return false;
            }
        }
        return true;
    }

    private String lifecycleRejection(RetrievedChunk chunk,
                                      DocumentLifecycleStatus lifecycle,
                                      LocalDateTime now) {
        if (lifecycle != DocumentLifecycleStatus.ACTIVE) {
            return "LIFECYCLE_" + lifecycle.name();
        }
        try {
            LocalDateTime from = parseTime(chunk.effectiveFrom());
            LocalDateTime to = parseTime(chunk.effectiveTo());
            if (from != null && now.isBefore(from)) {
                return "NOT_YET_EFFECTIVE";
            }
            if (to != null && !now.isBefore(to)) {
                return "EXPIRED";
            }
            return null;
        } catch (DateTimeParseException invalid) {
            return "INVALID_LIFECYCLE_TIME";
        }
    }

    private LocalDateTime parseTime(String value) {
        return StringUtils.hasText(value) ? LocalDateTime.parse(value.trim()) : null;
    }

    private List<ConflictGroup> findConflicts(List<RetrievedChunk> accepted) {
        Map<String, List<FactAssertion>> byFactAndScope = new LinkedHashMap<>();
        for (RetrievedChunk chunk : accepted) {
            String id = evidenceId(chunk);
            for (FactAssertion assertion : extractFacts(chunk, id)) {
                byFactAndScope.computeIfAbsent(assertion.factKey() + "|" + assertion.scope(),
                        ignored -> new ArrayList<>()).add(assertion);
            }
        }
        List<ConflictGroup> conflicts = new ArrayList<>();
        byFactAndScope.forEach((key, assertions) -> {
            Set<String> values = assertions.stream().map(FactAssertion::value)
                    .sorted().collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
            if (values.size() > 1) {
                List<String> ids = assertions.stream().map(FactAssertion::evidenceId).distinct().toList();
                FactAssertion first = assertions.get(0);
                conflicts.add(new ConflictGroup(first.factKey(), first.scope(), ids, List.copyOf(values),
                        "当前生效证据对同一事实给出不同值，需要权威依据或人工确认。"));
            }
        });
        return conflicts;
    }

    private List<FactAssertion> extractFacts(RetrievedChunk chunk, String evidenceId) {
        String content = nullSafe(chunk.content()).replaceAll("\\s+", "");
        Matcher matcher = FACT_PATTERN.matcher(content);
        List<FactAssertion> assertions = new ArrayList<>();
        while (matcher.find()) {
            String rawFact = matcher.group("fact");
            Set<String> models = extract(MODEL_PATTERN, rawFact);
            if (models.isEmpty()) {
                models = extract(MODEL_PATTERN, nullSafe(chunk.title()));
            }
            String condition = CONDITIONS.stream().filter(rawFact::contains).findFirst().orElse("UNSPECIFIED");
            String factKey = rawFact;
            for (String model : models) {
                factKey = factKey.replace(model, "");
            }
            for (String word : CONDITIONS) {
                factKey = factKey.replace(word, "");
            }
            factKey = factKey.replaceAll("^(第[一二三四五六七八九十\\d]+条)", "")
                    .replace("型号", "").replace("的", "");
            if (factKey.length() > 24) {
                factKey = factKey.substring(factKey.length() - 24);
            }
            String scope = (models.isEmpty() ? "UNSPECIFIED" : String.join(",", models)) + "|" + condition;
            String unit = normalizeUnit(matcher.group("unit"));
            String value = normalizeNumber(matcher.group("value")) + unit;
            assertions.add(new FactAssertion(factKey + "|" + unit, scope, value, evidenceId));
        }
        return assertions;
    }

    private Set<String> queryTerms(String query) {
        String normalized = normalize(query);
        Set<String> terms = new LinkedHashSet<>();
        terms.addAll(extract(MODEL_PATTERN, normalized));
        terms.addAll(extract(FAULT_PATTERN, normalized));
        Matcher chinese = CHINESE_SEQUENCE.matcher(normalized);
        while (chinese.find()) {
            String sequence = chinese.group();
            for (int i = 0; i + 1 < sequence.length(); i++) {
                String term = sequence.substring(i, i + 2);
                if (!STOP_TERMS.contains(term)) {
                    terms.add(term);
                }
            }
        }
        return terms;
    }

    private Set<String> extract(Pattern pattern, String text) {
        Set<String> values = new LinkedHashSet<>();
        if (text == null) {
            return values;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(normalize(matcher.group()).replace("_", "-"));
        }
        return values;
    }

    private boolean requiresGenericClarification(String query, DeviceInformation information) {
        if (StringUtils.hasText(information.getDeviceModel())
                || StringUtils.hasText(information.getFaultCode())
                || !information.getObservedSymptoms().isEmpty()) {
            return false;
        }
        return query.contains("故障") || query.contains("报警") || query.contains("维修");
    }

    private EvidenceAssessment clarification(List<String> evidenceIds,
                                             List<String> rejected,
                                             AssessmentDetails details,
                                             ClarificationService.ClarificationResult clarification) {
        return result(EvidenceDecision.CLARIFY, ResultCode.MISSING_REQUIRED_CONTEXT.name(),
                clarification.clarificationQuestion(), evidenceIds, List.of(), rejected, List.of(),
                clarification.missingInformation(), details);
    }

    private EvidenceAssessment result(EvidenceDecision decision,
                                      String reasonCode,
                                      String reason,
                                      List<String> evidenceIds,
                                      List<String> accepted,
                                      List<String> rejected,
                                      List<ConflictGroup> conflicts,
                                      List<String> missing,
                                      AssessmentDetails details) {
        return new EvidenceAssessment(decision, reasonCode, reason, evidenceIds, accepted,
                rejected, conflicts, missing, details);
    }

    private AssessmentDetails details(int retrieved, int effective, List<EvidenceAssessmentDetail> details) {
        return new AssessmentDetails(ALGORITHM, properties.getMinimumQueryCoverage(),
                properties.getDirectStatementCoverage(), retrieved, effective, details);
    }

    private String evidenceId(RetrievedChunk chunk) {
        if (StringUtils.hasText(chunk.chunkId())) {
            return chunk.chunkId();
        }
        if (StringUtils.hasText(chunk.vectorId())) {
            return chunk.vectorId();
        }
        return nullSafe(chunk.documentId()) + "#" + chunk.chunkIndex();
    }

    private String normalize(String value) {
        return nullSafe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String normalizeUnit(String unit) {
        if ("°C".equalsIgnoreCase(unit)) {
            return "℃";
        }
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "mpa" -> "MPa";
            case "kpa" -> "kPa";
            case "pa" -> "Pa";
            case "bar" -> "bar";
            case "kw" -> "kW";
            case "w" -> "W";
            case "v" -> "V";
            case "a" -> "A";
            case "hz" -> "Hz";
            case "rpm" -> "rpm";
            case "mm" -> "mm";
            case "cm" -> "cm";
            default -> unit;
        };
    }

    private String normalizeNumber(String value) {
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record Evaluation(boolean effective,
                              EvidenceCoverage coverageType,
                              double coverage,
                              List<String> matchedTerms,
                              List<String> missingTerms,
                              String lifecycleStatus,
                              String rejectionReason) {
    }

    private record FactAssertion(String factKey, String scope, String value, String evidenceId) {
    }
}

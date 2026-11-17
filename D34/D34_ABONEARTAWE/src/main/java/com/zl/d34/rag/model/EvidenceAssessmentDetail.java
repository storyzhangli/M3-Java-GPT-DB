package com.zl.d34.rag.model;

import java.util.List;

/** 单条证据的可审计评估明细。 */
public record EvidenceAssessmentDetail(String evidenceId,
                                       EvidenceCoverage coverage,
                                       double queryCoverage,
                                       List<String> matchedTerms,
                                       List<String> missingTerms,
                                       String lifecycleStatus,
                                       boolean accepted,
                                       String rejectionReason) {
    public EvidenceAssessmentDetail {
        matchedTerms = List.copyOf(matchedTerms);
        missingTerms = List.copyOf(missingTerms);
    }
}

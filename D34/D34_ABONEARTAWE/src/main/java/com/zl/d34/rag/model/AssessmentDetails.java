package com.zl.d34.rag.model;

import java.util.List;

/** 证据评估算法、阈值和逐条证据明细。 */
public record AssessmentDetails(String coverageAlgorithm,
                                double minimumQueryCoverage,
                                double directStatementCoverage,
                                int retrievedCount,
                                int effectiveCount,
                                List<EvidenceAssessmentDetail> evidenceDetails) {
    public AssessmentDetails {
        evidenceDetails = List.copyOf(evidenceDetails);
    }
}

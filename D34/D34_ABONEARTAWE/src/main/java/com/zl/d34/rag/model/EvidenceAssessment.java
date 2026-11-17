package com.zl.d34.rag.model;

import java.util.List;

/**
 * 证据评估结果。保留接受、拒绝、冲突与缺失条件，禁止只返回 hasEvidence 布尔值。
 */
public record EvidenceAssessment(EvidenceDecision decision,
                                 String reasonCode,
                                 String reason,
                                 List<String> evidenceIds,
                                 List<String> acceptedEvidenceIds,
                                 List<String> rejectedEvidenceIds,
                                 List<ConflictGroup> conflictGroups,
                                 List<String> missingInformation,
                                 AssessmentDetails assessmentDetails) {
    public EvidenceAssessment {
        evidenceIds = List.copyOf(evidenceIds);
        acceptedEvidenceIds = List.copyOf(acceptedEvidenceIds);
        rejectedEvidenceIds = List.copyOf(rejectedEvidenceIds);
        conflictGroups = List.copyOf(conflictGroups);
        missingInformation = List.copyOf(missingInformation);
    }
}

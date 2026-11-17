package com.zl.d34.rag.model;

import java.util.List;

/** 生成后校验结果：引用合法性与事实支持关系分别保留证据。 */
public record CitationValidationResult(String sanitizedAnswer,
                                       List<String> citedEvidenceIds,
                                       List<String> unsupportedClaims,
                                       int invalidCitationCount,
                                       boolean valid,
                                       String reasonCode) {
    public CitationValidationResult {
        citedEvidenceIds = List.copyOf(citedEvidenceIds);
        unsupportedClaims = List.copyOf(unsupportedClaims);
    }
}

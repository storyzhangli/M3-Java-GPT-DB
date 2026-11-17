package com.zl.d34.rag.model;

/** 证据门禁业务决策，与检索/生成技术状态分离。 */
public enum EvidenceDecision {
    ANSWER,
    CLARIFY,
    REFUSE,
    CONFLICT
}

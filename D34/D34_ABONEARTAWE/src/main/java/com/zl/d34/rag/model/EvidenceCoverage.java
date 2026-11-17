package com.zl.d34.rag.model;

/** 单条证据对问题的覆盖类型；词面评估不冒充模型语义推理。 */
public enum EvidenceCoverage {
    DIRECT,
    INFERENCE,
    UNCOVERED
}

package com.zl.d34.document.vector.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 原文库与向量库一致性核对结果。
 *
 * <p>status 使用任务书统一口径：PASS / FAIL / BLOCKED；仅表示本次核对结果。</p>
 */
@Data
public class VectorIndexConsistencyView {

    private String documentId;

    private int documentVersion;

    private String taskVersion;

    private String taskStatus;

    private int chunkCount;

    private int indexedChunkCount;

    private int vectorCount;

    private int missingVectorCount;

    private int orphanVectorCount;

    private boolean staleDocumentVersion;

    private String status;

    private List<String> findings = new ArrayList<>();

    /** 仅返回脱敏后的 ID 列表，最多 20 条，便于定位但不刷屏。 */
    private List<String> missingChunkIds = new ArrayList<>();

    private List<String> orphanVectorIds = new ArrayList<>();
}

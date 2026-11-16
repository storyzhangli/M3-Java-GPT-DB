package com.zl.d33.rag.model;

import java.util.List;

/**
 * 上下文组装环节产出（组装环节只处理已检索 Chunk，不触发新检索）。
 *
 * <p>预算口径诚实声明：{@code unit} 固定为 {@link #UNIT_UTF16}（UTF-16 code unit 字符长度），
 * 是字符估算而非 Token 计数，不伪称 Token；预算截断采取整块丢弃策略并计数，
 * 不切碎内容，进入上下文的来源映射不丢。</p>
 *
 * @param chunks      去重、按分数降序排序、受预算约束后的最终片段（进入上下文的完整集合）
 * @param totalChars  最终上下文总字符数（UTF-16 code unit，恒 &le; budgetChars）
 * @param budgetChars 本次预算上限（UTF-16 code unit）
 * @param truncated   是否发生预算截断（丢弃了尾部超出预算的片段）
 * @param droppedCount 被丢弃的片段数（含空白内容过滤与预算截断，不含重复去重）
 * @param unit        长度口径，固定 UTF16_CODE_UNIT
 */
public record AssembledContext(List<RetrievedChunk> chunks,
                               int totalChars,
                               int budgetChars,
                               boolean truncated,
                               int droppedCount,
                               String unit) {

    /** 长度口径：UTF-16 code unit（字符长度，与 chunk.length-unit 既有口径一致，非 Token）。 */
    public static final String UNIT_UTF16 = "UTF16_CODE_UNIT";
}

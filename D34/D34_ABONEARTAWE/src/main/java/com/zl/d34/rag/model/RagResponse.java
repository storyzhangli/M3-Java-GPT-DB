package com.zl.d34.rag.model;

import java.util.List;

/**
 * RAG 最小闭环响应对象（纯数据，不含业务逻辑）。
 *
 * <p>回答内容 + 来源列表 + 检索状态 + 生成状态四段契约：</p>
 * <ul>
 *     <li>{@code status}/{@code message}：五状态枚举名与可读说明，拒答分支可核验；</li>
 *     <li>{@code answer}：SUCCESS 时为经 CitationRegistry 清洗后的回答；拒答分支为受控文案；</li>
 *     <li>{@code sources}：实际被答案引用的来源（cited），无引用时为空列表；</li>
 *     <li>{@code retrieval}/{@code generation}：两个环节的结构化摘要，其中
 *     {@code generation.invoked=false} 是「未调用生成模型」的响应级可核验证据。</li>
 * </ul>
 *
 * @param status     五状态枚举名（NO_HIT / NO_USABLE_CONTEXT / RETRIEVAL_FAILED / GENERATION_FAILED / SUCCESS）
 * @param message    状态可读说明（对外文案，不含堆栈/连接信息）
 * @param answer     最终回答（SUCCESS 时为清洗后答案；否则为受控拒答文案）
 * @param sources    实际被引用的来源列表（无引用为空列表）
 * @param retrieval  检索环节摘要
 * @param generation 生成环节摘要（invoked=false 表示未调用生成模型）
 */
public record RagResponse(String status,
                          String message,
                          String answer,
                          List<RagSource> sources,
                          RetrievalState retrieval,
                          GenerationState generation,
                          String decision,
                          String reasonCode,
                          EvidenceAssessment evidence) {

    /** 兼容 D33 六参数构造；新调用方应显式提供证据决策。 */
    public RagResponse(String status,
                       String message,
                       String answer,
                       List<RagSource> sources,
                       RetrievalState retrieval,
                       GenerationState generation) {
        this(status, message, answer, sources, retrieval, generation, null, null, null);
    }

    /**
     * 检索环节摘要。
     *
     * @param query         用户问题原文（trim 后）
     * @param topK          实际生效的 Top-K
     * @param hitCount      检索命中数
     * @param queryDimensions Query 向量维度
     * @param appliedFilter 实际生效的 metadata 过滤表达式（可为 null）
     * @param elapsedMs     检索耗时（毫秒）
     */
    public record RetrievalState(String query,
                                 int topK,
                                 int hitCount,
                                 Integer queryDimensions,
                                 String appliedFilter,
                                 long elapsedMs) {
    }

    /**
     * 生成环节摘要。
     *
     * @param invoked                是否实际调用了生成模型（拒答分支 false，可核验零调用）
     * @param mode                   实际处理模式（mock / deepseek；未调用时为 null）
     * @param model                  实际生效的模型标识（未调用时为 null）
     * @param mocked                 是否为离线模拟结果
     * @param droppedUnknownMarkers  引用清洗中剥离的未知编号次数
     * @param contextChars           实际进入生成环节的上下文字符数（UTF-16 code unit）
     * @param truncated              上下文是否发生预算截断
     */
    public record GenerationState(boolean invoked,
                                  String mode,
                                  String model,
                                  boolean mocked,
                                  int droppedUnknownMarkers,
                                  int contextChars,
                                  boolean truncated) {
    }
}

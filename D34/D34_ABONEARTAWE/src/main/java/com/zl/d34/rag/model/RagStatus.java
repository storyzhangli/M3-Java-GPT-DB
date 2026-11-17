package com.zl.d34.rag.model;

/**
 * RAG 最小闭环五状态枚举（按 ResultCode 风格落地，可区分、不重叠）。
 *
 * <p>状态语义与「检索失败 / 检索成功无结果 / 有结果无可用上下文 / 生成失败」严格分离：</p>
 * <ul>
 *     <li>{@link #NO_HIT}：检索成功（向量库正常返回）但零命中，未调用生成模型；</li>
 *     <li>{@link #NO_USABLE_CONTEXT}：检索有命中，但经去重/过滤/预算约束后无可用上下文，未调用生成模型；</li>
 *     <li>{@link #RETRIEVAL_FAILED}：检索环节抛基础设施异常（向量库不可用等），不得伪装成空检索；</li>
 *     <li>{@link #GENERATION_FAILED}：检索与组装成功，但模型调用失败（超时/上游异常）；</li>
 *     <li>{@link #SUCCESS}：检索、组装、生成全部完成，回答经引用清洗后返回。</li>
 * </ul>
 */
public enum RagStatus {

    /** 检索成功无命中，未调用生成模型。 */
    NO_HIT("检索成功，但知识库中无相关命中，未调用生成模型"),

    /** 有检索结果但无可用上下文，未调用生成模型。 */
    NO_USABLE_CONTEXT("检索有结果，但未能组装出可用上下文，未调用生成模型"),

    /** 检索基础设施异常。 */
    RETRIEVAL_FAILED("检索服务暂不可用"),

    /** 模型生成失败。 */
    GENERATION_FAILED("模型生成失败"),

    /** 全链路成功。 */
    SUCCESS("检索、组装与生成均成功");

    /** 状态可读说明（对外文案，不含堆栈与连接信息）。 */
    private final String description;

    RagStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

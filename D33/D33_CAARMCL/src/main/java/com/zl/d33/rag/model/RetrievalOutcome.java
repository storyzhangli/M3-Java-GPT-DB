package com.zl.d33.rag.model;

import java.util.List;

/**
 * 检索环节产出（命中集合 + 检索执行摘要），由 RetrievalService 返回、RagOrchestrator 消费。
 *
 * @param hits                   按相似度降序的命中片段（可能为空列表，空列表不等于检索失败）
 * @param topK                   实际生效的 Top-K
 * @param elapsedMs              检索耗时（毫秒，含 Query Embedding）
 * @param queryDimensions        Query 向量维度（应与入库 384 一致）
 * @param appliedFilterExpression 实际生效的 metadata 过滤表达式（documentId 过滤时非空）
 */
public record RetrievalOutcome(List<RetrievedChunk> hits,
                               int topK,
                               long elapsedMs,
                               Integer queryDimensions,
                               String appliedFilterExpression) {
}

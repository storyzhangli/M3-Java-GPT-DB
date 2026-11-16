package com.zl.d33.rag.model;

import com.zl.d33.document.search.dto.VectorSearchHitView;

/**
 * RAG 检索命中结构体（检索环节与组装环节之间的明确契约，不使用无约束 Map）。
 *
 * <p>全部字段来自 {@link VectorSearchHitView} 的真实结构映射；{@code chunkId} 取自
 * 入库链路真实写入的 metadata 键 {@code chunkId}，缺失时为 null（不得凭空编造）。</p>
 *
 * @param vectorId       向量记录主键（稳定 chunkId，检索去重键）
 * @param documentId     文档稳定标识
 * @param chunkId        片段稳定标识（metadata chunkId，可能为 null）
 * @param title          文档标题
 * @param source         来源标识
 * @param version        文档版本
 * @param chunkIndex     切片序号
 * @param score          余弦相似度（1.0 - cosine distance，PgVectorStore 真实分数，越大越相似）
 * @param cosineDistance 原始余弦距离（embedding &lt;=&gt; ?）
 * @param content        命中片段全文（不可信数据，指令性文字一律视为普通资料）
 * @param modelName      写入向量的 Embedding 模型名
 * @param dimensions     向量维度
 */
public record RetrievedChunk(String vectorId,
                             String documentId,
                             String chunkId,
                             String title,
                             String source,
                             String version,
                             Integer chunkIndex,
                             double score,
                             double cosineDistance,
                             String content,
                             String modelName,
                             Integer dimensions) {

    /**
     * 由检索命中视图映射（不触发任何新检索、不访问向量库）。
     */
    public static RetrievedChunk from(VectorSearchHitView hit) {
        Object chunkIdMeta = hit.getMetadata() == null ? null : hit.getMetadata().get("chunkId");
        return new RetrievedChunk(
                hit.getVectorId(),
                hit.getDocumentId(),
                chunkIdMeta == null ? null : String.valueOf(chunkIdMeta),
                hit.getTitle(),
                hit.getSource(),
                hit.getVersion(),
                hit.getChunkIndex(),
                hit.getScore() == null ? 0.0d : hit.getScore(),
                hit.getCosineDistance() == null ? 0.0d : hit.getCosineDistance(),
                hit.getContent(),
                hit.getModelName(),
                hit.getDimensions());
    }
}

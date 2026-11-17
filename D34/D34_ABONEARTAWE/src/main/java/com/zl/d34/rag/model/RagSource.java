package com.zl.d34.rag.model;

/**
 * RAG 回答来源条目（结构化来源列表的单元，只能由后端按真实注册表组装）。
 *
 * <p>模型输出的文件路径、URL、页码、来源编号一律不得进入本结构；每个条目必须能回溯到
 * 检索环节真实命中的 Chunk（documentId/chunkId 对得上）。</p>
 *
 * @param citationId 请求级引用编号（S1、S2……，由 CitationRegistry 按真实命中分配）
 * @param documentId 真实文档稳定标识
 * @param chunkId    真实片段稳定标识
 * @param title      文档标题
 * @param source     来源标识
 * @param version    文档版本
 * @param chunkIndex 切片序号
 * @param score      该片段的检索分数（余弦相似度）
 */
public record RagSource(String citationId,
                        String documentId,
                        String chunkId,
                        String title,
                        String source,
                        String version,
                        Integer chunkIndex,
                        Double score) {
}

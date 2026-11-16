package com.zl.d33.rag;

import com.zl.d33.rag.model.RetrievedChunk;
import com.zl.d33.service.AiChatResult;

/**
 * RAG 测试共用夹具：构造确定性命中片段与模型结果，保证九类场景断言可复现。
 */
final class RagTestFixtures {

    private RagTestFixtures() {
    }

    /** 构造一条检索命中（score 为余弦相似度，越大越相似）。 */
    static RetrievedChunk chunk(String vectorId, String documentId, String chunkId,
                                String title, Integer chunkIndex, double score, String content) {
        return new RetrievedChunk(vectorId, documentId, chunkId, title, "MANUAL", "v1",
                chunkIndex, score, 1.0d - score, content, "local-hashing-embedding", 384);
    }

    /** mock 模式离线结果。 */
    static AiChatResult result(String content) {
        return new AiChatResult("mock", "deepseek-flash", true, false, content, null);
    }

    /** 构造重复文本（用于预算/去重场景的确定性长内容）。 */
    static String repeat(String unit, int times) {
        return unit.repeat(times);
    }
}

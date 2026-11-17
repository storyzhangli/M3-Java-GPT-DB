package com.zl.d34.rag;

import com.zl.d34.rag.dto.RagQueryRequest;
import com.zl.d34.rag.model.RetrievedChunk;
import com.zl.d34.service.AiChatResult;

/** D34 测试夹具；液压机/CNC 数据沿用 D33 确定性语料，生命周期场景使用隔离 fixture。 */
public final class RagTestFixtures {

    private RagTestFixtures() {
    }

    public static RetrievedChunk chunk(String vectorId, String documentId, String chunkId,
                                       String title, Integer chunkIndex, double score, String content) {
        return new RetrievedChunk(vectorId, documentId, chunkId, title, "MANUAL", "v1",
                chunkIndex, score, 1.0d - score, content, "local-hashing-embedding", 384,
                "ACTIVE", null, null);
    }

    public static RetrievedChunk chunk(String vectorId, String documentId, String chunkId,
                                       String title, Integer chunkIndex, double score, String content,
                                       String lifecycleStatus, String effectiveFrom, String effectiveTo) {
        return new RetrievedChunk(vectorId, documentId, chunkId, title, "MANUAL", "v1",
                chunkIndex, score, 1.0d - score, content, "local-hashing-embedding", 384,
                lifecycleStatus, effectiveFrom, effectiveTo);
    }

    public static AiChatResult result(String content) {
        return new AiChatResult("deepseek", "deepseek-flash", false, false, content, null);
    }

    public static RagQueryRequest request(String query) {
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery(query);
        return request;
    }
}

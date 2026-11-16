package com.zl.d33.rag.service;

import com.zl.d33.rag.model.AssembledContext;
import com.zl.d33.rag.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文组装服务测试：去重、排序、空白过滤、预算边界（恰好等于预算 / 超预算截断且来源映射不丢）。
 */
class ContextAssemblyServiceTest {

    private final ContextAssemblyService service = new ContextAssemblyService();

    private RetrievedChunk chunk(String vectorId, double score, String content) {
        return new RetrievedChunk(vectorId, "doc-1", "c-" + vectorId, "手册", "MANUAL", "v1",
                0, score, 1.0d - score, content, "local-hashing-embedding", 384);
    }

    @Test
    void dedupeKeepsHighestScoreOccurrenceAndDropsDuplicate() {
        RetrievedChunk a = chunk("v1", 0.9d, "内容A");
        RetrievedChunk aDup = chunk("v1", 0.7d, "内容A重复");
        RetrievedChunk b = chunk("v2", 0.8d, "内容B");

        // 输入按真实检索顺序（分数降序）：同键首次出现即最高分，去重保留首次出现
        AssembledContext context = service.assemble(List.of(a, aDup, b), 1000);

        assertEquals(2, context.chunks().size());
        assertEquals(0.9d, context.chunks().get(0).score(), 1e-9);
        assertEquals("v1", context.chunks().get(0).vectorId());
        assertEquals("v2", context.chunks().get(1).vectorId());
        assertFalse(context.truncated());
    }

    @Test
    void sortByScoreDescThenChunkIndexAscThenVectorId() {
        RetrievedChunk c1 = new RetrievedChunk("v2", "doc-1", "c-v2", "手册", "MANUAL", "v1",
                0, 0.8d, 0.2d, "同分A", "local-hashing-embedding", 384);
        RetrievedChunk c2 = new RetrievedChunk("v1", "doc-1", "c-v1", "手册", "MANUAL", "v1",
                1, 0.8d, 0.2d, "同分B", "local-hashing-embedding", 384);
        RetrievedChunk c3 = new RetrievedChunk("v3", "doc-1", "c-v3", "手册", "MANUAL", "v1",
                null, 0.6d, 0.4d, "低分", "local-hashing-embedding", 384);

        AssembledContext context = service.assemble(List.of(c3, c2, c1), 1000);

        // 分数降序；同分按 chunkIndex 升序（c1.idx0 在 c2.idx1 前）；低分与空 chunkIndex 最后
        assertEquals(List.of("v2", "v1", "v3"),
                context.chunks().stream().map(RetrievedChunk::vectorId).toList());
    }

    @Test
    void blankContentFilteredAndCounted() {
        RetrievedChunk blank1 = chunk("v1", 0.9d, "   ");
        RetrievedChunk blank2 = chunk("v2", 0.8d, null);
        RetrievedChunk valid = chunk("v3", 0.7d, "有效内容");

        AssembledContext context = service.assemble(List.of(blank1, blank2, valid), 1000);

        assertEquals(1, context.chunks().size());
        assertEquals(2, context.droppedCount());
    }

    @Test
    void budgetExactlyFitsAllKeptAndNotTruncated() {
        RetrievedChunk a = chunk("v1", 0.9d, "12345");
        RetrievedChunk b = chunk("v2", 0.8d, "67890");

        AssembledContext context = service.assemble(List.of(a, b), 10);

        assertEquals(2, context.chunks().size());
        assertEquals(10, context.totalChars());
        assertEquals(10, context.budgetChars());
        assertFalse(context.truncated());
    }

    @Test
    void budgetOverrunTruncatesWholeChunksAndKeepsSourceMapping() {
        RetrievedChunk a = chunk("v1", 0.9d, "AAAA");
        RetrievedChunk b = chunk("v2", 0.8d, "BBBB");
        RetrievedChunk c = chunk("v3", 0.7d, "CCCC");

        AssembledContext context = service.assemble(List.of(a, b, c), 8);

        assertEquals(2, context.chunks().size());
        assertEquals(8, context.totalChars());
        assertTrue(context.truncated());
        assertEquals(1, context.droppedCount());
        // 进入上下文的来源映射不丢：前两条完整保留且字段与检索命中一致
        assertEquals(List.of("v1", "v2"),
                context.chunks().stream().map(RetrievedChunk::vectorId).toList());
        assertEquals("doc-1", context.chunks().get(0).documentId());
        assertEquals("c-v2", context.chunks().get(1).chunkId());
    }

    @Test
    void budgetZeroOrNegativeDropsAll() {
        AssembledContext context = service.assemble(List.of(chunk("v1", 0.9d, "内容")), 0);

        assertTrue(context.chunks().isEmpty());
        assertTrue(context.truncated());
        assertEquals(AssembledContext.UNIT_UTF16, context.unit());
    }

    @Test
    void nullInputTreatedAsEmpty() {
        AssembledContext context = service.assemble(null, 100);
        assertTrue(context.chunks().isEmpty());
        assertFalse(context.truncated());
        assertEquals(0, context.totalChars());
        assertEquals(AssembledContext.UNIT_UTF16, context.unit());
    }
}

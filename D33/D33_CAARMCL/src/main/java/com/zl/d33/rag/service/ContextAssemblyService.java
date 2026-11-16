package com.zl.d33.rag.service;

import com.zl.d33.rag.model.AssembledContext;
import com.zl.d33.rag.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文组装服务：Chunk 去重、排序、长度预算控制、来源组织。
 *
 * <p>硬性边界：只处理已检索 Chunk（{@link RetrievedChunk}），<b>不触发任何新检索</b>、
 * 不访问 VectorStore、不调用 ChatModel。</p>
 *
 * <p>处理顺序与口径：</p>
 * <ol>
 *     <li>过滤空白内容片段并计数；</li>
 *     <li>按 vectorId（稳定 chunkId，向量主键）去重，同键保留首次出现的最高分记录；</li>
 *     <li>按余弦相似度降序、chunkIndex 升序、vectorId 字典序稳定排序；</li>
 *     <li>按 UTF-16 字符预算整块截断：累计长度不超过预算的片段全部进入上下文，
 *     下一条导致超预算的片段及之后全部丢弃并计数——整块丢弃、不切碎内容，
 *     不破坏证据内容，进入上下文的来源映射不丢。</li>
 * </ol>
 */
@Slf4j
@Service
public class ContextAssemblyService {

    /**
     * 组装已检索 Chunk 为受预算约束的最终上下文。
     *
     * @param hits        检索命中的片段（可含重复与空白）
     * @param budgetChars 字符预算（UTF-16 code unit）；&le; 0 时全部丢弃
     * @return 组装结果；chunks 为空表示无可用上下文（由编排器映射 NO_USABLE_CONTEXT）
     */
    public AssembledContext assemble(List<RetrievedChunk> hits, int budgetChars) {
        List<RetrievedChunk> source = hits == null ? List.of() : hits;
        int droppedCount = 0;

        // 1. 过滤空白内容
        List<RetrievedChunk> nonBlank = new ArrayList<>();
        for (RetrievedChunk chunk : source) {
            if (StringUtils.hasText(chunk.content())) {
                nonBlank.add(chunk);
            } else {
                droppedCount++;
            }
        }

        // 2. 去重：vectorId（稳定 chunkId）优先，回落 documentId#chunkIndex；
        //    保持输入顺序即可让首次出现者进入（输入即按相似度降序）
        Map<String, RetrievedChunk> unique = new LinkedHashMap<>();
        for (RetrievedChunk chunk : nonBlank) {
            unique.putIfAbsent(dedupeKey(chunk), chunk);
        }

        // 3. 稳定排序：score 降序 → chunkIndex 升序 → vectorId 字典序
        List<RetrievedChunk> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing(RetrievedChunk::chunkIndex,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RetrievedChunk::vectorId,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        // 4. 预算截断：整块丢弃，不切碎
        List<RetrievedChunk> kept = new ArrayList<>();
        int totalChars = 0;
        boolean truncated = false;
        for (RetrievedChunk chunk : sorted) {
            int length = chunk.content().length();
            if (totalChars + length > budgetChars) {
                truncated = true;
                droppedCount += sorted.size() - kept.size();
                break;
            }
            kept.add(chunk);
            totalChars += length;
        }

        AssembledContext context = new AssembledContext(List.copyOf(kept), totalChars, budgetChars,
                truncated, droppedCount, AssembledContext.UNIT_UTF16);
        log.info("上下文组装完成：命中={}，去重后={}，进入上下文={}，字符={}/{}，truncated={}，丢弃={}",
                source.size(), unique.size(), kept.size(), totalChars, budgetChars,
                truncated, droppedCount);
        return context;
    }

    /**
     * 去重键：vectorId 优先；缺失时回落 documentId#chunkIndex，两者皆无则按对象身份去重。
     */
    private String dedupeKey(RetrievedChunk chunk) {
        if (StringUtils.hasText(chunk.vectorId())) {
            return "v:" + chunk.vectorId();
        }
        if (StringUtils.hasText(chunk.documentId()) || chunk.chunkIndex() != null) {
            return "d:" + chunk.documentId() + "#" + chunk.chunkIndex();
        }
        return "o:" + System.identityHashCode(chunk);
    }
}

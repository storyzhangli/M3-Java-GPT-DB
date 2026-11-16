package com.zl.d33.rag.service;

import com.zl.d33.dto.CitationDto;
import com.zl.d33.knowledge.CitationRegistry;
import com.zl.d33.knowledge.KnowledgeChunk;
import com.zl.d33.rag.config.RagProperties;
import com.zl.d33.rag.dto.RagQueryRequest;
import com.zl.d33.rag.model.AssembledContext;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagSource;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.rag.model.RetrievalOutcome;
import com.zl.d33.rag.model.RetrievedChunk;
import com.zl.d33.service.AiChatResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 编排器：协调「检索 → 组装 → 生成」全流程，只做编排，不承载具体检索/模型细节。
 *
 * <p>硬性边界：不直接写 VectorStore 调用、不拼接 Prompt 细节（Prompt 构造在
 * GenerationService）；组件间只传明确数据对象（RetrievalOutcome/AssembledContext/RagResponse）。</p>
 *
 * <p>状态契约（与旧 KnowledgeChatOrchestrator 的空检索调模型行为并存，不破坏旧路径）：</p>
 * <ul>
 *     <li>检索异常 → RETRIEVAL_FAILED（不伪装成空检索）；</li>
 *     <li>检索成功零命中 → NO_HIT，<b>不调用生成模型</b>，返回受控拒答；</li>
 *     <li>有命中但组装后无可用上下文 → NO_USABLE_CONTEXT，<b>不调用生成模型</b>；</li>
 *     <li>模型调用异常（含超时）→ GENERATION_FAILED；</li>
 *     <li>全链路成功 → SUCCESS，回答经 CitationRegistry 清洗后返回，来源只由后端注册表组装。</li>
 * </ul>
 *
 * <p>ChatModel 零调用可核验：拒答分支直接返回且 {@code generation.invoked=false}；
 * 测试层另以 Mockito {@code verifyNoInteractions} 双重核验。</p>
 */
@Slf4j
@Service
public class RagOrchestrator {

    private final RetrievalService retrievalService;

    private final ContextAssemblyService assemblyService;

    private final GenerationService generationService;

    private final RagProperties properties;

    /** mock 模式（AI_MODE 非 deepseek）：离线确定性生成，服务端追加编号行保证闭环可演示。 */
    private final boolean mockMode;

    public RagOrchestrator(RetrievalService retrievalService,
                           ContextAssemblyService assemblyService,
                           GenerationService generationService,
                           RagProperties properties,
                           @Value("${app.ai.mode:mock}") String aiMode) {
        this.retrievalService = retrievalService;
        this.assemblyService = assemblyService;
        this.generationService = generationService;
        this.properties = properties;
        this.mockMode = !"deepseek".equalsIgnoreCase(aiMode);
    }

    /**
     * 执行 RAG 最小闭环问答。
     *
     * @param request 已通过 Controller 校验的请求（query 非空白）
     * @return 五状态之一的响应对象
     */
    public RagResponse ask(RagQueryRequest request) {
        String query = request.getQuery().trim();

        // 1. 检索：基础设施异常 → RETRIEVAL_FAILED，与空检索严格区分
        RetrievalOutcome outcome;
        try {
            outcome = retrievalService.retrieve(query, request.getTopK(), request.getDocumentId());
        } catch (RuntimeException error) {
            log.warn("RAG 检索失败，query={}，原因={}", query, error.getMessage());
            return refusal(RagStatus.RETRIEVAL_FAILED, properties.getRetrievalFailedMessage(),
                    query, null, null, false, 0);
        }

        // 2. 检索成功零命中 → NO_HIT，不调用生成模型
        if (outcome.hits().isEmpty()) {
            log.info("RAG 检索零命中，NO_HIT，跳过模型调用，query={}", query);
            return refusal(RagStatus.NO_HIT, properties.getNoHitAnswer(),
                    query, outcome, null, false, 0);
        }

        // 3. 上下文组装：去重/排序/预算（只处理已检索 Chunk，不触发新检索）
        AssembledContext context = assemblyService.assemble(outcome.hits(),
                properties.getContextCharBudget());

        // 4. 有结果但无可用上下文 → NO_USABLE_CONTEXT，不调用生成模型
        if (context.chunks().isEmpty()) {
            log.info("RAG 无可用上下文，NO_USABLE_CONTEXT，跳过模型调用，query={}", query);
            return refusal(RagStatus.NO_USABLE_CONTEXT, properties.getNoUsableContextAnswer(),
                    query, outcome, context, false, 0);
        }

        // 5. 引用注册表：编号按真实命中分配，模型只能使用这些编号
        List<KnowledgeChunk> knowledgeChunks = context.chunks().stream()
                .map(this::toKnowledgeChunk)
                .toList();
        CitationRegistry registry = new CitationRegistry(knowledgeChunks);

        // 6. 生成：模型异常（含超时）→ GENERATION_FAILED
        AiChatResult raw;
        try {
            raw = generationService.generate(query, registry.contextBlock());
        } catch (RuntimeException error) {
            log.warn("RAG 生成失败，query={}，原因={}", query, error.getMessage());
            return refusal(RagStatus.GENERATION_FAILED, properties.getGenerationFailedMessage(),
                    query, outcome, context, true, registry.getDroppedUnknownMarkers());
        }

        // 7. 引用清洗：mock 模式先由服务端追加合法编号行（编号全部来自真实注册表）
        String content = raw.content();
        if (mockMode) {
            content = nullSafe(content) + buildMockMarkerLine(registry);
        }
        String sanitized = registry.validateAndStrip(content);

        // 8. 来源列表：仅由注册表实际引用（cited）组装，模型无法凭空创建来源
        List<RagSource> sources = toSources(registry.citedCitations(), context.chunks());

        RagResponse response = new RagResponse(
                RagStatus.SUCCESS.name(),
                RagStatus.SUCCESS.getDescription(),
                sanitized,
                sources,
                retrievalState(query, outcome),
                generationState(true, raw, registry, context));
        log.info("RAG 闭环成功：status=SUCCESS，分配来源={}，实际引用={}，剥离未知编号={}，"
                        + "上下文字符={}/{}，truncated={}",
                registry.size(), sources.size(), registry.getDroppedUnknownMarkers(),
                context.totalChars(), context.budgetChars(), context.truncated());
        return response;
    }

    /** 拒答分支统一构造：invoked=false 是可核验的零调用证据。 */
    private RagResponse refusal(RagStatus status,
                                String answer,
                                String query,
                                RetrievalOutcome outcome,
                                AssembledContext context,
                                boolean generationAttempted,
                                int droppedUnknownMarkers) {
        int contextChars = context == null ? 0 : context.totalChars();
        boolean truncated = context != null && context.truncated();
        return new RagResponse(
                status.name(),
                status.getDescription(),
                answer,
                List.of(),
                retrievalState(query, outcome),
                new RagResponse.GenerationState(generationAttempted,
                        null, null, false, droppedUnknownMarkers, contextChars, truncated));
    }

    private RagResponse.RetrievalState retrievalState(String query, RetrievalOutcome outcome) {
        if (outcome == null) {
            return new RagResponse.RetrievalState(query, 0, 0, null, null, 0L);
        }
        return new RagResponse.RetrievalState(query, outcome.topK(), outcome.hits().size(),
                outcome.queryDimensions(), outcome.appliedFilterExpression(), outcome.elapsedMs());
    }

    private RagResponse.GenerationState generationState(boolean invoked,
                                                        AiChatResult raw,
                                                        CitationRegistry registry,
                                                        AssembledContext context) {
        return new RagResponse.GenerationState(invoked,
                invoked ? raw.mode() : null,
                invoked ? raw.model() : null,
                invoked && raw.mocked(),
                registry.getDroppedUnknownMarkers(),
                context.totalChars(),
                context.truncated());
    }

    /** RetrievedChunk → KnowledgeChunk 纯映射：section/page/url 检索元数据不承载，保持真实。 */
    private KnowledgeChunk toKnowledgeChunk(RetrievedChunk chunk) {
        return new KnowledgeChunk(chunk.documentId(), chunk.chunkId(), chunk.title(),
                chunk.source(), null, null, chunk.content(), null);
    }

    /** citedCitations → RagSource，chunkIndex/score/version 回查真实命中映射补齐。 */
    private List<RagSource> toSources(List<CitationDto> citations, List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> byChunkId = new HashMap<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.chunkId() != null) {
                byChunkId.putIfAbsent(chunk.chunkId(), chunk);
            }
        }
        return citations.stream()
                .map(citation -> {
                    RetrievedChunk chunk = byChunkId.get(citation.chunkId());
                    return new RagSource(citation.id(), citation.documentId(), citation.chunkId(),
                            citation.title(), citation.sourceType(),
                            chunk == null ? null : chunk.version(),
                            chunk == null ? null : chunk.chunkIndex(),
                            chunk == null ? null : chunk.score());
                })
                .toList();
    }

    /** mock 模式编号行：编号全部来自真实注册表，离线闭环仍可完整演示。 */
    private String buildMockMarkerLine(CitationRegistry registry) {
        StringBuilder line = new StringBuilder(" 参考来源：");
        registry.allCitations().forEach(c -> line.append('[').append(c.id()).append(']'));
        line.append('。');
        return line.toString();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

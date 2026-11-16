package com.zl.d33.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d33.document.chunk.model.ChunkStrategy;
import com.zl.d33.document.dto.DocumentImportView;
import com.zl.d33.document.service.DocumentImportService;
import com.zl.d33.document.vector.dto.VectorIndexTaskView;
import com.zl.d33.document.vector.service.DocumentVectorIndexService;
import com.zl.d33.rag.dto.RagQueryRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.rag.service.RagOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D33 RAG 真机端到端（显式门控：D33_RAG_IT=true 才执行）。
 *
 * <p>真实链路：文档导入（MySQL 台账）→ 向量入库（真实 PG/pgvector 384 维）→
 * 真实向量检索（cosine &lt;=&gt;，HNSW）→ 上下文组装 → 生成（AI_MODE=mock 离线模型）。
 * 测试不连接真实 DeepSeek（无 API Key 时），模型环节属离线模拟，不冒充真实模型端到端；
 * 测试前必须注入 PG_VECTOR_PASSWORD（缺失时启动阶段 fail-fast）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "D33_RAG_IT", matches = "true")
class D33RagEndToEndIntegrationTest {

    private static final String SOURCE = "D33-RAG-IT";

    private static final String TITLE = "D33端到端液压机故障手册";

    @Autowired
    private DocumentImportService importService;

    @Autowired
    private DocumentVectorIndexService indexService;

    @Autowired
    private RagOrchestrator orchestrator;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String corpusText = """
            液压机压力不足故障排查手册。
            第一条：液压机压力不足时，应先检查液压泵输出压力与溢流阀设定值。
            第二条：油温过高会导致油液粘度下降，进而造成系统压力波动，应检查冷却系统。
            第三条：系统压力波动也可能是吸油管路堵塞或油箱液位过低造成。
            """;

    @Test
    void importThenIndexThenAskAnswerableQuestion() {
        String documentId = importAndIndex();

        RagQueryRequest request = new RagQueryRequest();
        request.setQuery("液压机压力不足应该怎么排查？");
        request.setTopK(5);
        request.setDocumentId(documentId);

        RagResponse response = orchestrator.ask(request);

        assertEquals(RagStatus.SUCCESS.name(), response.status());
        assertNotNull(response.answer());
        // 真实检索命中：hitCount > 0，维度 384（与入库一致）
        assertTrue(response.retrieval().hitCount() > 0);
        assertEquals(384, response.retrieval().queryDimensions());
        // documentId 过滤生效：来源全部属于导入文档
        assertEquals("documentId == '" + documentId + "'", response.retrieval().appliedFilter());
        for (var source : response.sources()) {
            assertEquals(documentId, source.documentId());
        }
        // mock 模式服务端编号行保证闭环可演示：来源编号必然可回溯
        assertTrue(response.generation().invoked());
        assertTrue(response.generation().mocked());
        assertFalse(response.answer().contains("[S99]"));
    }

    @Test
    void askIrrelevantQuestionReturnsNoHitWithoutModelInvocation() {
        // 过滤一个不存在的 documentId：真实检索必然零命中（过滤下推，不伪造）
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery("任何问题");
        request.setTopK(5);
        request.setDocumentId("d33-no-such-document");

        RagResponse response = orchestrator.ask(request);

        assertEquals(RagStatus.NO_HIT.name(), response.status());
        assertEquals(0, response.retrieval().hitCount());
        // 响应级零调用证据
        assertFalse(response.generation().invoked());
    }

    @Test
    void httpEndpointAskReturnsStructuredResponse() throws Exception {
        String documentId = importAndIndex();
        RagQueryRequest request = new RagQueryRequest();
        request.setQuery("液压机压力不足怎么排查？");
        request.setDocumentId(documentId);

        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(RagStatus.SUCCESS.name()))
                .andExpect(jsonPath("$.data.retrieval.hitCount").isNumber())
                .andExpect(jsonPath("$.data.retrieval.queryDimensions").value(384))
                .andExpect(jsonPath("$.data.generation.invoked").value(true));
    }

    @Test
    void httpEndpointEmptyQueryRejectedWithParamInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    /** 真实导入 + 真实向量入库，返回文档稳定 ID。 */
    private String importAndIndex() {
        MockMultipartFile file = new MockMultipartFile("file", "d33-e2e-hydraulic.txt",
                "text/plain", corpusText.getBytes(StandardCharsets.UTF_8));
        DocumentImportView imported = importService.importFile(file, null, SOURCE, TITLE);
        String documentId = imported.getDocument().getDocumentId();
        assertNotNull(documentId);

        VectorIndexTaskView task = indexService.indexDocument(documentId, ChunkStrategy.FIXED_SIZE);
        VectorIndexTaskView settled = waitForSettlement(documentId, task);
        assertTrue(Set.of("COMPLETED", "PARTIAL").contains(settled.getStatus()),
                "向量入库任务未达可用终态：" + settled.getStatus() + "，error=" + settled.getErrorMessage());
        return documentId;
    }

    /** 有限轮询等待索引任务进入终态（COMPLETED/PARTIAL/FAILED），避免无限等待。 */
    private VectorIndexTaskView waitForSettlement(String documentId, VectorIndexTaskView initial) {
        VectorIndexTaskView current = initial;
        for (int i = 0; i < 10 && !isTerminal(current.getStatus()); i++) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            current = indexService.currentTask(documentId);
        }
        return current;
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status);
    }
}

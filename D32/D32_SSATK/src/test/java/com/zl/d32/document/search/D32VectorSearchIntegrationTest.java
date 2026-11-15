package com.zl.d32.document.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d32.document.chunk.model.ChunkStrategy;
import com.zl.d32.document.embedding.service.DocumentEmbeddingService;
import com.zl.d32.document.search.dto.VectorSearchRequest;
import com.zl.d32.document.search.dto.VectorSearchResultView;
import com.zl.d32.document.search.service.DocumentVectorSearchService;
import com.zl.d32.document.service.DocumentImportService;
import com.zl.d32.document.vector.service.DocumentDeletionService;
import com.zl.d32.document.vector.service.DocumentVectorIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D32 相似度检索真机集成测试（显式门控，默认不执行）。
 *
 * <p>门控：环境变量 {@code D32_SEARCH_IT=true}；真实连接 MySQL ai_db（文档台账）与
 * PostgreSQL spring_ai（pgvector 向量），不使用 Mock 结果冒充检索。</p>
 *
 * <p>链路：导入设备故障语料（8 篇 28 片段）→ FIXED_SIZE 索引入库 → 核查 vector_store
 * 实际 metadata → 10 个真实业务问题逐条 Top-5 检索（stdout 打 D32_EVIDENCE 标记）→
 * documentId 过滤与空过滤验证 → Top-1 余弦相似度自算交叉验证分数语义。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.vectorstore.pgvector.initialize-schema=true",
                "spring.ai.vectorstore.pgvector.schema-validation=false",
                "app.ai.chunk.chunk-size=180",
                "app.ai.chunk.chunk-overlap=20"
        })
@EnabledIfEnvironmentVariable(named = "D32_SEARCH_IT", matches = "true")
class D32VectorSearchIntegrationTest {

    private static final String CORPUS_SOURCE = "d32-search-corpus";

    /** 证据输出目录：M3-Java/docs/evidence/D32（基于模块目录相对定位）。 */
    private static final java.nio.file.Path EVIDENCE_DIR = java.nio.file.Paths
            .get(System.getProperty("user.dir"), "..", "..", "docs", "evidence", "D32").normalize();

    private static final List<String> QUERIES = List.of(
            "数控机床主轴过热报警应该怎么处理？",
            "伺服电机老是报过载，机床一动就跳，先查什么？",
            "液压机油温总是偏高，夏天更明显，怎么排查？",
            "液压缸保压时间越来越短是什么原因？",
            "空压机机房粉尘大，机器经常高温跳机怎么解决？",
            "变频器一减速就报过压，制动电阻是好的，还有什么可能？",
            "PLC和上位机通讯老是中断，怎么彻底解决？",
            "设备故障工单关闭前必须填写哪些内容？",
            "整条产线停机的故障，工单应该定几级？多长时间内响应？",
            "轴承发热还有异响，怀疑润滑失效，补充润滑脂要注意什么？");

    @Autowired
    private DocumentImportService importService;

    @Autowired
    private DocumentVectorIndexService vectorIndexService;

    @Autowired
    private DocumentDeletionService documentDeletionService;

    @Autowired
    private DocumentVectorSearchService searchService;

    @Autowired
    private DocumentEmbeddingService embeddingService;

    @Autowired
    @Qualifier("pgDataSource")
    private DataSource pgDataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<String> importedDocumentIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String documentId : importedDocumentIds) {
            try {
                documentDeletionService.deleteDocument(documentId);
            } catch (RuntimeException ignored) {
                // 尽力清理；保留断言错误优先。
            }
        }
        importedDocumentIds.clear();
    }

    @Test
    @DisplayName("语料入库 → 实际 metadata 核查 → 10 问题 Top-5 检索 → metadata 过滤 → 分数交叉验证")
    void corpusTop5EvaluationAndFilterAndScoreSemantics() throws Exception {
        List<String> documentIds = importAndIndexCorpus();
        assertThat(documentIds).hasSize(8);

        JdbcTemplate pg = new JdbcTemplate(pgDataSource);

        // 证据 1：vector_store 实际 metadata 字段与归属计数（不得凭空虚构过滤字段）
        Integer total = pg.queryForObject("SELECT count(*) FROM vector_store", Integer.class);
        Integer managedD32 = pg.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'managedBy' = 'd32'", Integer.class);
        List<String> metadataSamples = pg.queryForList(
                "SELECT metadata::text FROM vector_store ORDER BY id LIMIT 3", String.class);
        System.out.println("D32_EVIDENCE|SEARCH|vectorStoreTotal=" + total + "|managedByD32=" + managedD32);
        java.nio.file.Files.createDirectories(EVIDENCE_DIR);
        java.nio.file.Files.write(EVIDENCE_DIR.resolve("02-vector-store-metadata-samples.txt"),
                ("vector_store.total=" + total + "\nvector_store.managedBy=d32 count=" + managedD32
                        + "\n\n" + String.join("\n\n", metadataSamples) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        for (String sample : metadataSamples) {
            // 控制台仅输出 ASCII 摘要，完整 UTF-8 内容已写入 02 号证据文件
            System.out.println("D32_EVIDENCE|SEARCH|metadataSampleLen=" + sample.length());
        }
        assertThat(total).isGreaterThanOrEqualTo(documentIds.size());
        assertThat(managedD32).isGreaterThanOrEqualTo(documentIds.size());

        // 证据 2：10 个真实业务问题逐条 Top-5 检索
        int questionNo = 0;
        List<String> evaluationLines = new ArrayList<>();
        VectorSearchResultView firstQuestionResult = null;
        String firstQuestion = null;
        for (String query : QUERIES) {
            questionNo++;
            VectorSearchRequest request = new VectorSearchRequest();
            request.setQuery(query);
            request.setTopK(5);
            VectorSearchResultView view = searchService.search(request);

            assertThat(view.getTopK()).isEqualTo(5);
            assertThat(view.getQueryDimensions()).isEqualTo(384);
            assertThat(view.getHits().size()).isLessThanOrEqualTo(5);
            assertThat(view.getHits()).allSatisfy(hit -> {
                assertThat(hit.getScore()).isNotNull();
                assertThat(hit.getScore()).isBetween(0.0d, 1.0d);
                assertThat(hit.getCosineDistance()).isNotNull();
                assertThat(hit.getDocumentId()).isNotBlank();
            });
            // 按相似度降序
            for (int i = 1; i < view.getHits().size(); i++) {
                assertThat(view.getHits().get(i - 1).getScore())
                        .isGreaterThanOrEqualTo(view.getHits().get(i).getScore());
            }

            String json = objectMapper.writeValueAsString(view);
            System.out.println("D32_EVIDENCE|SEARCH_Q" + questionNo + "|hitCount=" + view.getHitCount()
                    + "|top1DocId=" + (view.getHits().isEmpty() ? "-" : view.getHits().get(0).getDocumentId())
                    + "|top1Score=" + (view.getHits().isEmpty() ? "-" : view.getHits().get(0).getScore()));
            evaluationLines.add("### Q" + questionNo + " " + query + "\n```json\n" + json + "\n```\n");
            if (firstQuestionResult == null) {
                firstQuestionResult = view;
                firstQuestion = query;
            }
        }
        java.nio.file.Files.write(EVIDENCE_DIR.resolve("03-top5-retrieval-evaluation.md"),
                String.join("\n", evaluationLines).getBytes(StandardCharsets.UTF_8));
        assertThat(firstQuestionResult).isNotNull();

        // 证据 3：documentId metadata 过滤——命中必须全部属于目标文档
        String targetDocumentId = firstQuestionResult.getHits().get(0).getDocumentId();
        VectorSearchRequest filtered = new VectorSearchRequest();
        filtered.setQuery(firstQuestion);
        filtered.setTopK(20);
        filtered.setDocumentId(targetDocumentId);
        VectorSearchResultView filteredView = searchService.search(filtered);
        assertThat(filteredView.getHits()).isNotEmpty();
        assertThat(filteredView.getHits()).allSatisfy(
                hit -> assertThat(hit.getDocumentId()).isEqualTo(targetDocumentId));
        System.out.println("D32_EVIDENCE|SEARCH_FILTER|documentId=" + targetDocumentId
                + "|hits=" + filteredView.getHitCount()
                + "|scores=" + filteredView.getHits().stream().map(h -> String.valueOf(h.getScore())).toList());

        // 证据 4：不存在的 documentId 过滤 → 0 命中（过滤真实生效，不静默忽略）
        VectorSearchRequest missing = new VectorSearchRequest();
        missing.setQuery(firstQuestion);
        missing.setDocumentId("DOC-NOT-EXIST-999");
        VectorSearchResultView missingView = searchService.search(missing);
        assertThat(missingView.getHitCount()).isZero();
        System.out.println("D32_EVIDENCE|SEARCH_FILTER_MISSING|hits=0");

        // 证据 5：Top-1 分数交叉验证——score = 1 - cosine distance，且与自算余弦一致
        var topHit = firstQuestionResult.getHits().get(0);
        String embeddingText = pg.queryForObject(
                "SELECT embedding::text FROM vector_store WHERE id = ?::uuid",
                String.class, topHit.getVectorId());
        float[] docVector = parsePgVector(embeddingText);
        float[] queryVector = embeddingService.embedQuery(firstQuestion);
        double selfSimilarity = cosine(queryVector, docVector);
        System.out.println("D32_EVIDENCE|SCORE_CHECK|score=" + topHit.getScore()
                + "|cosineDistance=" + topHit.getCosineDistance()
                + "|selfSimilarity=" + selfSimilarity);
        java.nio.file.Files.write(EVIDENCE_DIR.resolve("04-filter-and-score-check.txt"),
                ("filter: documentId=" + targetDocumentId
                        + " hits=" + filteredView.getHitCount()
                        + " scores=" + filteredView.getHits().stream().map(h -> String.valueOf(h.getScore())).toList()
                        + "\nfilter-missing: DOC-NOT-EXIST-999 hits=0"
                        + "\nscore-check: score=" + topHit.getScore()
                        + " cosineDistance=" + topHit.getCosineDistance()
                        + " selfSimilarity=" + selfSimilarity
                        + " |score-(1-distance)|=" + Math.abs(topHit.getScore() - (1.0d - topHit.getCosineDistance()))
                        + " |selfSimilarity-score|=" + Math.abs(selfSimilarity - topHit.getScore())
                        + "\n").getBytes(StandardCharsets.UTF_8));
        assertThat(topHit.getScore()).isCloseTo(1.0d - topHit.getCosineDistance(),
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(selfSimilarity).isCloseTo(topHit.getScore(),
                org.assertj.core.data.Offset.offset(1e-4));
    }

    /** 导入语料 8 篇并逐篇建立 FIXED_SIZE 向量索引。 */
    private List<String> importAndIndexCorpus() throws Exception {
        String corpusJson = new String(new ClassPathResource("knowledge/equipment-fault-corpus.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode corpus = objectMapper.readTree(corpusJson);
        List<String> documentIds = new ArrayList<>();
        for (JsonNode docNode : corpus) {
            String title = docNode.get("title").asText();
            StringBuilder markdown = new StringBuilder();
            for (JsonNode chunk : docNode.get("chunks")) {
                markdown.append(chunk.get("section").asText()).append('\n')
                        .append(chunk.get("excerpt").asText()).append("\n\n");
            }
            MockMultipartFile file = new MockMultipartFile("file", "corpus-" + docNode.get("documentId").asText()
                    + ".txt", "text/plain", markdown.toString().getBytes(StandardCharsets.UTF_8));
            var importView = importService.importFile(file, null, CORPUS_SOURCE, title);
            String documentId = importView.getDocument().getDocumentId();
            documentIds.add(documentId);
            importedDocumentIds.add(documentId);
        }
        for (String documentId : documentIds) {
            var task = vectorIndexService.indexDocument(documentId, ChunkStrategy.STRUCTURE);
            assertThat(task.getStatus()).isEqualTo("COMPLETED");
            System.out.println("D32_EVIDENCE|SEARCH_INDEX|documentId=" + documentId
                    + "|chunks=" + task.getChunkCount());
        }
        return documentIds;
    }

    private float[] parsePgVector(String text) {
        String body = text.substring(1, text.length() - 1);
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * (double) b[i];
            normA += (double) a[i] * (double) a[i];
            normB += (double) b[i] * (double) b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

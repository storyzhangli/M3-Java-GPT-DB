package com.zl.d31.document.vector;

import com.zl.d31.document.dto.DocumentImportView;
import com.zl.d31.document.exception.DocumentValidationException;
import com.zl.d31.document.service.DocumentImportService;
import com.zl.d31.document.service.DocumentRegistrationService;
import com.zl.d31.document.vector.service.DocumentDeletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D31 HTTP 接口真机验证：真实 Spring Boot Web 容器 + 真实 MySQL/PostgreSQL。
 *
 * <p>显式门控 {@code D31_VECTOR_IT=true}；不使用 MockMvc 代替真实 HTTP 调用。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.vectorstore.pgvector.initialize-schema=true",
                "spring.ai.vectorstore.pgvector.schema-validation=false"
        })
@EnabledIfEnvironmentVariable(named = "D31_VECTOR_IT", matches = "true")
class D31VectorIndexHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DocumentImportService importService;

    @Autowired
    private DocumentRegistrationService documentService;

    @Autowired
    private DocumentDeletionService documentDeletionService;

    private String documentId;

    @AfterEach
    void cleanUp() {
        if (documentId == null) {
            return;
        }
        try {
            documentDeletionService.deleteDocument(documentId);
        } catch (RuntimeException ignored) {
            // 已由 HTTP 删除接口删除时忽略；不掩盖原始断言错误。
        }
    }

    @Test
    @DisplayName("HTTP 接口完成向量索引、状态查询、一致性核对与文档删除")
    void httpEndpointsWorkAgainstRealDatabases() {
        String source = "D31-HTTP-" + UUID.randomUUID();
        String title = "D31 HTTP 接口文档-" + UUID.randomUUID();
        DocumentImportView imported = importService.importFile(new MockMultipartFile(
                        "file", "d31-http.txt", "text/plain",
                        ("D31 HTTP 向量接口验证。" + "设备异常振动排查。".repeat(80))
                                .getBytes(StandardCharsets.UTF_8)),
                null, source, title);
        documentId = imported.getDocument().getDocumentId();
        assertThat(documentService.getByDocumentId(documentId).getStatus().name()).isEqualTo("COMPLETED");

        ResponseEntity<String> indexResponse = restTemplate.exchange(
                "/api/v1/documents/{documentId}/vector-index?strategy=FIXED_SIZE",
                HttpMethod.POST, HttpEntity.EMPTY, String.class, documentId);
        assertThat(indexResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(indexResponse.getBody()).contains("\"code\":200");
        assertThat(indexResponse.getBody()).contains("\"status\":\"COMPLETED\"");

        ResponseEntity<String> statusResponse = restTemplate.getForEntity(
                "/api/v1/documents/{documentId}/vector-index", String.class, documentId);
        assertThat(statusResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(statusResponse.getBody()).contains("\"status\":\"COMPLETED\"");

        ResponseEntity<String> consistencyResponse = restTemplate.getForEntity(
                "/api/v1/documents/{documentId}/vector-index/consistency", String.class, documentId);
        assertThat(consistencyResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(consistencyResponse.getBody()).contains("\"status\":\"PASS\"");

        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                "/api/v1/documents/{documentId}", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class, documentId);
        assertThat(deleteResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(deleteResponse.getBody()).contains("\"documentDeleted\":true");
        assertThatThrownBy(() -> documentService.getByDocumentId(documentId))
                .isInstanceOf(DocumentValidationException.class);
        documentId = null;
    }
}

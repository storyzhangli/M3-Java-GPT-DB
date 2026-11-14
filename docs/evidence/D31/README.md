# D31 证据目录索引

> 归档时间：2026-11-15
> 环境：Windows 本机 + MySQL `ai_db` + 虚拟机 `192.168.200.153:5432` 的 PostgreSQL `spring_ai` / pgvector 0.8.7
> 说明：本目录只保存脱敏后的结构、计数、状态和接口响应；不保存数据库口令、API Key 或完整业务正文。

## 文件清单

| 文件 | 内容 | 采集方式 |
|---|---|---|
| `01-mysql-schema-and-rows.txt` | `knowledge_document`、`knowledge_document_page`、`vector_index_task`、`vector_chunk_index` 的实际 DDL、唯一键、索引、行数 | `D31EvidenceMysql` JDBC 直连 |
| `02-pgvector-schema-and-rows.txt` | PostgreSQL 版本、pgvector 版本、`vector(384)` 列、HNSW cosine 索引、`vector_store` 总行数与 D31 管理行数 | `D31EvidencePg` JDBC 直连 |
| `03-vector-integration-evidence.txt` | 5 个真机集成测试的 `D31_EVIDENCE`、失败/部分/一致性快照及 Maven 结果 | `D31_VECTOR_IT=true` 真机测试 |
| `04-http-endpoint-evidence.txt` | 真实 Tomcat 下的 `HTTP_RESPONSE`，覆盖导入、查询、一致性、删除 | `D31_VECTOR_IT=true` + `@SpringBootTest(RANDOM_PORT)` |
| `05-full-test-suite-summary.txt` | D31 clean test 全量 Maven 测试最后结果 | `mvn clean test` |
| `06-git-status.txt` | 分支、HEAD、工作区状态与 diff stat | `git status` / `git diff --stat` |
| `07-six-scenarios.md` | 六类场景 + 一致性 + 同内容隔离的验收证据表 | 由 `03`/`04` 整理，不新增执行结论 |

## 关键结论索引

- MySQL 实际唯一键：
  - `knowledge_document`：`uk_knowledge_document_id(document_id)`、`uk_document_content_hash(document_id,content_hash)`；旧 `uk_knowledge_content_hash` 已不存在。
  - `knowledge_document_page`：`uk_knowledge_document_page(document_id,page_number)`。
  - `vector_index_task`：`uk_vector_task_doc_version(document_id,version)`。
  - `vector_chunk_index`：`uk_vector_chunk_chunk_id(chunk_id)`、`uk_vector_chunk_doc_version_index(document_id,version,chunk_index)`。
- PostgreSQL：`vector_store` 的 `embedding` 为 `vector(384)`，索引为 `hnsw (embedding vector_cosine_ops)`。
- 集成测试：5/5 通过；HTTP 测试：1/1 通过；全量测试：294 通过、0 失败、0 跳过。
- 测试结束清理后：MySQL 四张 D31 表均为 0 行；PG `vector_store` 总行数为 0、D31 管理行数为 0。

## 复现命令（口令仅从环境变量注入，不写入仓库）

```powershell
$env:JAVA_HOME = 'D:\SoftwareInstall\JDK\JDK21'
$env:MYSQL_PASSWORD = '<本机 MySQL 口令>'
$env:PG_PASSWORD = '<PG 口令>'
$env:D31_VECTOR_IT = 'true'
$env:D31_PG_IT = 'true'

& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' `
  -f 'E:\AI\project\AI-Learning-three-GPT\M3-Java\D31\pom.xml' `
  -pl D31_VSUAI test
```

## 边界说明

- 本证据只证明 D31 当前代码、当前数据库和当前测试输入下的行为，不等于生产容量、故障注入全覆盖或安全渗透结论。
- `05-full-test-suite-summary.txt` 是全量测试的摘要；完整 Maven 输出在 `logs/D31/D31-full-mvn-test.log`。
- `03`/`04` 中的中文控制台日志可能出现终端编码乱码，但 ASCII 状态、ID、计数与 JSON 响应字段保持完整；正式文档只引用 ASCII 字段和已脱敏 JSON。
- 所有测试数据在测试结束时已清理；`vector_index_task.id` 等自增序列可能前进，表中没有残留业务行。

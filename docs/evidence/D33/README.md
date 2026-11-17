# D33 证据目录说明

> Day 33：上下文组装与 RAG 最小闭环。证据按迭代编号归档（docs/evidence/D33/），
> 测试摘要与日志同列，全部为本机真实执行产物，未虚构任何数字。

| 文件 | 内容 | 来源 |
|---|---|---|
| `00-env-probe.txt` | 阶段一环境探测：mvn 3.9.16/JDK 21.0.7、git HEAD=`1c187dd`（=任务书基线）、PG 192.168.200.153:5432 可达、main=218/test=0、pom 依赖核对、维度 384/COSINE/HNSW/managed-by=d33/remove-existing=false、search default-top-k=5/max-top-k=20、关键类全在位、.gitignore 核查 | `docs\temp_docs\D33\probe_d33.ps1` |
| `02-git-status.txt` | git status 快照与 diff --stat：修改仅限 .gitignore、D33 application.yml 与 DeepSeek 配置类，新增 rag 包/测试树/证据/迭代记录，无 D30–D32 改动 | git 命令重定向 |
| `03-mysql-table-columns.txt` | 旧 knowledge_document 实际列核查（source/content_length/version int，确认旧结构） | `ProbeMysqlTable.java` |
| `04-mysql-four-tables.txt` | 四张表实际结构：vector_index_task/vector_chunk_index 为新结构，两张文档表为旧结构 | `ProbeMysqlSchema.java` |
| `05-row-count.txt` | 两张旧表行数=0（DROP 重建无数据损失证据） | `ProbeRowCount.java` |
| `06-rebuild-tables.txt` | 授权后 DROP 重建留痕：重建后列与 D31 权威 DDL 一致（source_system/file_size/parser_*） | `MysqlRebuildTables.java` |
| `demo-rag-e2e-output.txt` | mock 端到端演示留痕：真实导入 documentId=21cd26c1…COMPLETED → 入库 1 chunk → SUCCESS（hitCount=1/invoked=true）→ NO_HIT（invoked=false），含完整响应 JSON | `demo_rag_e2e.ps1` |

## 测试证据（logs/D33/）

| 日志 | 结果 |
|---|---|
| `D33-clean-test.log` | 全量 `mvn clean test`：**Tests run: 33, Failures: 0, Errors: 0, Skipped: 4**，BUILD SUCCESS（表重建与配置修改后回归仍全绿） |
| `D33-rag-it.log` | mock 真机门控（D33_RAG_IT=true，真实 PG/MySQL，AI_MODE=mock）：**4/0/0/0**，BUILD SUCCESS |
| `D33-rag-it-deepseek.log` | **真实 DeepSeek 门控（AI_MODE=deepseek，mocked=false）：4/0/0/0**，BUILD SUCCESS；真实模型调用仅 2 次、totalTokens=452、回答带合法 [S1] 引用 |
| `D33-compile.log` | 主代码编译记录（EXIT=0） |
| `demo-app-run.log` | 演示脚本启动的应用运行日志 |

## 测试覆盖（33 用例）

- 九类必测场景：RagAnswerableEndToEndTest(2) / RagIrrelevantQuestionTest(2) / RagEmptyRetrievalTest(2) /
  RagNoUsableContextTest(2) / RagGenerationTimeoutTest(2) / RagRetrievalFailureTest(2) /
  RagInvalidCitationTest(2) / RagDuplicateChunkTest(2) / RagBudgetOverrunTest(2)
- 服务级：ContextAssemblyServiceTest(7)、GenerationServiceTest(4)
- 门控真机：D33RagEndToEndIntegrationTest(4)

## 安全核查

- 日志与证据目录经 `sk-` 密钥模式扫描，无 API Key 泄露；AI_API_KEY/PG_VECTOR_PASSWORD 真实值仅存在于用户环境变量，未写入任何文件。

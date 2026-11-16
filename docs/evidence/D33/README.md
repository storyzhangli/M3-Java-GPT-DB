# D33 证据目录说明

> Day 33：上下文组装与 RAG 最小闭环。证据按迭代编号归档（docs/evidence/D33/），
> 测试摘要与日志同列，全部为本机真实执行产物，未虚构任何数字。

| 文件 | 内容 | 来源 |
|---|---|---|
| `00-env-probe.txt` | 阶段一环境探测：mvn 3.9.16/JDK 21.0.7、git HEAD=`1c187dd`（=任务书基线）、PG 192.168.200.153:5432 可达、main=218/test=0、pom 依赖核对、维度 384/COSINE/HNSW/managed-by=d33/remove-existing=false、search default-top-k=5/max-top-k=20、关键类全在位、.gitignore 核查 | `docs\temp_docs\D33\probe_d33.ps1` 执行输出 |
| `02-git-status.txt` | 实施后 git status 快照与 diff --stat：修改仅限 .gitignore（+5 行）与 D33 application.yml（+24 行），新增 rag 包/测试树/证据/迭代记录，无 D30–D32 改动 | git 命令重定向 |
| `demo-rag-e2e-output.txt` | 端到端演示留痕：真实导入 documentId=21cd26c1…COMPLETED → 向量入库 COMPLETED（1 chunk）→ RAG SUCCESS（hitCount=1/sources=1/invoked=true）→ NO_HIT（invoked=false 零调用证据），含完整响应 JSON | `docs\temp_docs\D33\demo_rag_e2e.ps1` 执行留痕 |

## 测试证据（logs/D33/）

| 日志 | 结果 |
|---|---|
| `D33-clean-test.log` | 全量 `mvn clean test`：**Tests run: 33, Failures: 0, Errors: 0, Skipped: 4**，BUILD SUCCESS（Skipped 4=门控真机测试） |
| `D33-rag-it.log` | 门控真机（D33_RAG_IT=true，真实 PG/MySQL，AI_MODE=mock）：**Tests run: 4, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS |
| `D33-compile.log` | 主代码编译记录（EXIT=0） |
| `demo-app-run.log` | 演示脚本启动的应用运行日志 |

## 测试覆盖（33 用例）

- 九类必测场景：RagAnswerableEndToEndTest(2) / RagIrrelevantQuestionTest(2) / RagEmptyRetrievalTest(2) /
  RagNoUsableContextTest(2) / RagGenerationTimeoutTest(2) / RagRetrievalFailureTest(2) /
  RagInvalidCitationTest(2) / RagDuplicateChunkTest(2) / RagBudgetOverrunTest(2)
- 服务级：ContextAssemblyServiceTest(7)、GenerationServiceTest(4)
- 门控真机：D33RagEndToEndIntegrationTest(4)

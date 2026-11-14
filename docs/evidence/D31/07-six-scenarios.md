# D31 六类场景与跨库一致性证据表

> 采集时间：2026-11-15 02:38–02:40（Asia/Shanghai）
> 采集方式：`D31_VECTOR_IT=true` 真机集成测试，真实 MySQL `ai_db` + 真实 PostgreSQL `spring_ai` + pgvector 0.8.7。
> 原始证据：`docs/evidence/D31/03-vector-integration-evidence.txt`、`docs/evidence/D31/04-http-endpoint-evidence.txt`。
> 说明：下列 documentId、Chunk ID 均来自一次真实运行，ID 由稳定算法生成；换一次运行会得到不同的测试文档 ID，但同一输入重试时 ID 必须一致。

## 1. 场景总览

| 编号 | 验收场景 | 实测测试方法 | 最终结果 | 关键证据 |
|---|---|---|---|---|
| S1 | 新增入库 | `lifecycleNewDuplicateUpdateDelete` | COMPLETED，3 Chunks/3 Vectors | `afterFirstIndex` |
| S2 | 重复导入 / 重复索引 | 同上 | `duplicate=true`，任务仍 1 条、attempt 仍 1 | `afterDuplicateImport`、`afterDuplicateIndex` |
| S3 | 文档更新 | 同上 | v2 切换成功，v1 变 SUPERSEDED 并清理 | `afterVersion2Import`、`afterVersion2Failed`、`afterVersion2Retry` |
| S4 | 文档与向量删除 | 同上 | 文档、页面、台账、向量全部删除 | `beforeDelete` + 删除日志 |
| S5 | 全失败后重试 | `failedThenRetryRecovers` | FAILED→COMPLETED，Chunk ID 不变 | `D31_FAILED_STATE`、`afterRetryRecovered` |
| S6 | 部分失败恢复 | `partialFailureThenRetryOnlyMissing` | PARTIAL（4/5）→COMPLETED（5/5） | `D31_PARTIAL_STATE`、`afterRetryRecovered` |
| S7 | 跨库一致性补偿 | `consistencyDetectsMissingAndOrphanThenRepairs` | FAIL（missing=1、orphan=1）→PASS | `D31_CONSISTENCY_STATE`、`D31_GLOBAL_STATE` |
| S8 | 同内容不同文档隔离 | `sameContentDifferentDocumentsStaySeparate` | 两文档各自 4 Chunks/4 Vectors | `sameContent|first`、`sameContent|second` |

## 2. S1 新增入库

**操作前状态**：测试文档尚未登记；MySQL `knowledge_document`、`vector_index_task`、`vector_chunk_index` 均无该文档记录；PostgreSQL 无对应 UUID 向量。

**执行操作**：

1. 以固定 source/title 导入文本，文本被解析为 page 1；
2. 文档状态落为 `COMPLETED`，版本 `1`；
3. 调用 `DocumentVectorIndexService.indexDocument(documentId, FIXED_SIZE)`；
4. 切分、逐 Chunk Embedding、PG upsert、DB 侧计数核对。

**实际变化（documentId=`80826eea7399ad9108074a119069c0ef`）**：

| 阶段 | 文档状态 | 版本 | MySQL Chunk 台账 | PG 向量 | 任务台账 |
|---|---|---:|---:|---:|---|
| 新增导入后 | COMPLETED | 1 | 0 | 0 | `[]` |
| 首次索引后 | COMPLETED | 1 | 3 | 3 | `[1:COMPLETED:attempt=1]` |

**稳定 Chunk ID（首次索引）**：

- `73872083-1fd2-5966-b59f-fe66f533644e`
- `f9e2810b-b89b-5809-8568-e98a22a76d3e`
- `13378c13-dce3-5147-bbe5-0606e1ca6d7e`

**结论**：新增文档从“无 Chunk、无向量、无任务”变为“Chunk 数=向量数=任务完成”，不是仅调用一次写入接口即宣称成功；向量行数来自 PostgreSQL 实际 `COUNT(*)`。

## 3. S2 重复导入与重复索引

**执行操作**：

1. 用相同 source/title、相同内容再次导入；
2. 对已完成的同一文档再次调用索引。

**实际变化**：

| 阶段 | 文档版本 | Chunk 数 | 向量数 | 任务条数 / attempt |
|---|---:|---:|---:|---|
| 重复导入后 | 1 | 0 | 0 | 0 条 |
| 重复索引后 | 1 | 3 | 3 | 1 条 / attempt=1 |

**判定依据**：重复导入返回 `duplicate=true`；重复索引命中“任务 COMPLETED + 当前版本 Chunk ID 集合与 PG 实际 ID 集合一致”，直接返回 `duplicate=true`，不重新 Embedding、不新增任务行。任务仍为 `[1:COMPLETED:attempt=1]`，证明 attempt 未自增。

**边界**：这里的“重复”是同一业务身份（documentId 或 source+title）且内容哈希相同、文档已完成。相同内容但 source/title 不同属于两个文档，不能按内容哈希合并，见 S8。

## 4. S3 文档更新（版本切换）

**执行操作**：

1. 同一 documentId 导入内容变化的新文本，`DocumentImportService` 将版本从 `1` 递增到 `2`；
2. 先让 v2 的 Embedding 全部失败，观察旧版本是否保留；
3. 恢复 Embedding 后重试 v2；
4. v2 完成后核对旧版本清理。

**实际变化（同一 documentId）**：

| 阶段 | 文档版本 | MySQL Chunk 台账 | PG 向量 | 任务台账 |
|---|---|---:|---:|---|
| v2 导入后、索引前 | 2 | 3（仅旧 v1） | 3（旧 v1 保留） | `[1:COMPLETED:attempt=1]` |
| v2 全部失败后 | 2 | 8（v1 的 3 + v2 的 5） | 3（旧 v1 仍保留） | `[1:COMPLETED:attempt=1, 2:FAILED:attempt=1]` |
| v2 重试成功后 | 2 | 5（仅 v2） | 5（仅 v2） | `[1:SUPERSEDED:attempt=1, 2:COMPLETED:attempt=2]` |

**v2 稳定 Chunk ID**：

- `9696943d-2825-5f12-8827-5512965555af`
- `2f84f081-dbf0-59e1-87bc-42df673e6370`
- `be75de5e-d8e0-5389-94c0-13c62a56823b`
- `f85beb69-97ac-5b25-ae6a-0762a7fd04a9`
- `13c36ae4-8872-5b8c-9cfb-31bdf1085f06`

**结论**：更新不是“先删旧再写新”，而是旧版本保留到新版本完整成功；新版本失败时旧向量仍可被检索，避免更新窗口内知识库完全不可用。新版本完成后 v1 任务被标记 `SUPERSEDED`，旧 Chunk 台账被清理。

## 5. S4 删除

**操作前状态**：文档版本 2，Chunks=5，Vectors=5，任务 2 条（v1 SUPERSEDED、v2 COMPLETED）。

**执行操作**：`DELETE /api/v1/documents/{documentId}`，由 `DocumentDeletionService` 先删 PostgreSQL 向量，再在 MySQL 事务内删台账、页面、文档。

**实际变化**：

- 删除日志：`chunkRows=5, taskRows=2, vectorRows=5`；
- 文档删除成功；
- 测试进一步断言文档、页面、Chunk 台账、任务台账、PG 向量均不存在。

**结论**：删除覆盖文档本体、Chunk 快照、任务状态与向量本体；不是仅删 MySQL 文档行。

## 6. S5 全失败后重试

**触发方式**：Spy `EmbeddingModel` 对所有 Chunk 抛 `IllegalStateException`，`app.ai.embedding.max-attempts=2`，让每个 Chunk 的调用失败。

**实际变化（documentId=`f5bc088ee84b6ff53720abdfeeb700b0`）**：

| 阶段 | 任务状态 | indexed/total | failed | PG 向量 | 任务 attempt |
|---|---|---:|---:|---:|---:|
| 全失败后 | FAILED | 0/3 | 3 | 0 | 1 |
| 解除故障后重试 | COMPLETED | 3/3 | 0 | 3 | 2 |

**重试前后 Chunk ID 完全一致**：

- `fd832c8b-8c96-5341-a08c-4823f117a97f`
- `6b1a10bd-0d53-5d48-bcea-e153d5c72029`
- `5f5067a1-9385-57f6-b505-8285b0745a9d`

**结论**：失败不伪造成功；重试复用同一稳定 ID 与同一任务行，只更新状态和 attempt，不产生新任务/新 Chunk。

## 7. S6 部分失败恢复

**触发方式**：只让第 2 个 Chunk（`chunkIndex=1`）的 Embedding 失败，其余 4 个成功。

**实际变化（documentId=`9cd0ba6d21b67edc174a015d6884dc8e`）**：

| 阶段 | 任务状态 | indexed/total | failed | PG 向量 | 任务 attempt |
|---|---|---:|---:|---:|---:|
| 部分失败后 | PARTIAL | 4/5 | 1 | 4 | 1 |
| 重试后 | COMPLETED | 5/5 | 0 | 5 | 2 |

**重试前后 Chunk ID 完全一致**：

- `b010b6a9-97a7-545b-8ab2-a4c902d3a4be`
- `35d80e4b-5cad-559b-9d51-eb470d40c7db`
- `7659a077-47a8-558f-9ab3-fbffa2598fb4`
- `f047d62d-3cf4-563b-9aa5-8eb5dec5536e`
- `5a5a7749-528d-5120-addd-60e969f2da58`

**结论**：`PARTIAL` 是真实中间态，已经成功的 4 条不重写；重试只补失败/缺失的 1 条，最终 5 条齐全。

## 8. S7 跨库一致性检查与补偿

**制造故障**：完整索引后，手动从 PG 删除 1 个该文档的向量，并插入 1 条 metadata 含 `managedBy=d31`、`documentId` 指向该文档、`version=999` 的孤儿向量。

**实际变化（documentId=`04c67e5b262394a821b99a6f6f8b1c82`）**：

| 阶段 | 一致性状态 | missing | orphan | 说明 |
|---|---|---:|---:|---|
| 篡改后检查 | FAIL | 1 | 1 | `findings=[MISSING_VECTOR, ORPHAN_VECTOR]` |
| 全局孤儿检查 | — | — | 1 | `checkGlobal` 看到 1 条 |
| 全局孤儿补偿 | — | — | 1 被删除 | `repairGlobalOrphans` 返回 deleted=1 |
| 文档级 `repair` 后 | PASS | 0 | 0 | 补齐缺失向量，任务 attempt=2 |
| 人工把台账 Chunk 改为 FAILED 后检查 | FAIL | 0 | 0 | `findings=[CHUNK_NOT_INDEXED]`；恢复 INDEXED 后 PASS |

**注意**：`afterTamper` 证据快照是在全局孤儿删除之后采集的，所以该行 `vectors=2`；这不影响“篡改时 missing=1、orphan=1、状态 FAIL”的断言。最终 `repair` 后向量恢复为 3。

**结论**：

- 缺失向量能被识别并补齐；
- 孤儿向量能被识别并删除；
- 台账 Chunk 被人工改为 FAILED 时，检查返回 `CHUNK_NOT_INDEXED`，不会被任务状态掩盖；
- 文档级与全局级补偿均已真实执行；
- 补偿后再次检查 PASS，不依赖“修完就宣称成功”。

## 9. S8 同内容不同文档隔离

**执行操作**：两个不同 source/title 的文档写入完全相同的文本内容。

**实际变化**：

| 文档 | documentId | contentHash | Chunks | Vectors | 任务 |
|---|---|---:|---:|---:|---|
| A | `dc92537fa2b5ca622c391c90b8ba2082` | 相同 | 4 | 4 | `[1:COMPLETED:attempt=1]` |
| B | `5ed6aee12e2eeea84c7c7604387ed9e2` | 相同 | 4 | 4 | `[1:COMPLETED:attempt=1]` |

**Chunk ID 各自独立**：

- A：`0049b502-3d05-573d-ac12-6aecfc50582b`、`54a97e4c-c3ef-513f-8249-452cc2ab6d1d`、`a0e232f8-9873-5e7f-964a-88123a7fd20e`、`335dbf9c-f744-52a8-890c-61c4d2374502`
- B：`128a065c-81f2-58ca-9969-5e13fb5ff58e`、`0c919b63-3aa5-5f06-b50d-32a154ca7a8d`、`f7173384-22ba-5255-872d-a406e3974d0c`、`4764ae8a-b275-580b-bb52-cc490a8493d9`

**结论**：旧全局唯一键 `uk_knowledge_content_hash(content_hash)` 已被迁移为 `uk_document_content_hash(document_id, content_hash)`；两个文档 contentHash 相同但 documentId 不同，能够各自登记、各自生成 Chunk、各自写入 4 条向量。这个场景是 D31 数据库约束改造是否真正生效的关键反证。

## 10. 真实 HTTP 接口证据

来源：`04-http-endpoint-evidence.txt`，测试文档 `1678f4fb593ae200145571215ca967c2`。

| 请求 | HTTP | 关键响应 |
|---|---:|---|
| `POST /api/v1/documents/{id}/vector-index?strategy=FIXED_SIZE` | 200 | `code=200`、`status=COMPLETED`、`chunkCount=2`、`indexedChunkCount=2`、`attemptCount=1`、`duplicate=false` |
| `GET /api/v1/documents/{id}/vector-index` | 200 | COMPLETED，stage=VERIFY |
| `GET /api/v1/documents/{id}/vector-index/consistency` | 200 | `status=PASS`、missing=0、orphan=0 |
| `DELETE /api/v1/documents/{id}` | 200 | `documentDeleted=true`、`deletedChunkRows=2`、`deletedTaskRows=1`、`deletedVectorRows=2`、`status=PASS` |

## 11. 测试结果摘要

- 真实集成测试：`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 真实 HTTP 测试：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 全量 D31 测试：`Tests run: 294, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

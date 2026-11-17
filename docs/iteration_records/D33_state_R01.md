# D33 迭代状态

## 需求编号
D33-R01（阶段二：按需精读与设计，2026-11-17）

## 精读范围（跨模块读取说明）
- `document/search/service/DocumentVectorSearchService`：确认真实检索入口签名 `search(VectorSearchRequest)->VectorSearchResultView`（含 topK 治理、documentId 过滤、elapsedMs/queryDimensions/hitCount 摘要字段）——本次检索通道的唯一事实来源。
- `document/search/dto/VectorSearchHitView`：命中字段清单（rank/content/score/cosineDistance/vectorId/documentId/version/title/source/chunkIndex/modelName/dimensions/metadata），chunkId 位于 metadata（真实字段，D32 已核验）。
- `knowledge/CitationRegistry`：引用校验 API 以 `KnowledgeChunk` 为注册单元，`contextBlock()` 生成带 [S1] 编号的受约束上下文，`validateAndStrip()` 剥离未知编号并计数——RAG 链路直接复用，不重写校验逻辑。
- `service/AiChatService/ChatInvocation/AiChatResult/ThinkingSettings`：生成侧唯一抽象（任务书 §1.5 强制走 AiChatService）；同步调用 `chat(ChatInvocation, ThinkingSettings)`。
- `knowledge/KnowledgeChatOrchestrator`：问题 B 位置已实证（无命中仍调 `aiChatService.chat`，属旧普通问答兜底）；**本次不修改该文件**，旧路径原样保留。
- `resilience/ModelResilienceSupport`：超时/重试由 AiChatService 内部治理，RagOrchestrator 只捕获异常归类 GENERATION_FAILED，不重复造重试层。
- `common/Result/ResultCode`：HTTP 响应统一契约。
- `application.yml`：AI 段（AI_MODE 默认 mock）、resilience 段、search 段（default-top-k=5/max-top-k=20）、chunk.length-unit=UTF16_CODE_UNIT（预算口径沿用）。
- `application-test.yml`：initialize-schema=false，真机测试靠门控（D33_RAG_IT=true）。

## 修改文件（本迭代范围）
### 新增（main，包 `com.zl.d33.rag`）
1. `rag/model/RagStatus.java` — 五状态枚举：NO_HIT / NO_USABLE_CONTEXT / RETRIEVAL_FAILED / GENERATION_FAILED / SUCCESS（按 ResultCode 风格，附可读 message）。
2. `rag/model/RetrievedChunk.java` — 检索命中结构体（record，明确字段，无 Map 契约）。
3. `rag/model/RetrievalOutcome.java` — 检索结果包装（hits + topK + elapsedMs + queryDimensions + appliedFilter）。
4. `rag/model/AssembledContext.java` — 组装结果（chunks/totalChars/budgetChars/truncated/droppedCount/unit）。
5. `rag/model/RagSource.java` — 来源条目（citationId/documentId/chunkId/title/source/version/chunkIndex/score）。
6. `rag/model/RagResponse.java` — 响应对象（status/message/answer/sources/retrieval/generation，纯数据）。
7. `rag/dto/RagQueryRequest.java` — 请求 DTO（query 必填、topK 可选、documentId 可选过滤）。
8. `rag/config/RagProperties.java` — `app.ai.rag` 配置绑定（全外置）。
9. `rag/service/RetrievalService.java` — Query Embedding→VectorStore→Top-K（包装 DocumentVectorSearchService；**禁止调用 ChatModel**）。
10. `rag/service/ContextAssemblyService.java` — 去重/排序/预算截断/来源组织（**不触发新检索**）。
11. `rag/service/GenerationService.java` — Prompt 构造→AiChatService→生成（**禁止访问 VectorStore**）。
12. `rag/service/RagOrchestrator.java` — 编排（拒答分支零模型调用，可核验）。
13. `rag/controller/RagController.java` — `POST /api/v1/rag/ask`。

### 修改（main）
14. `application.yml` — 追加 `app.ai.rag` 段（default-top-k/max-top-k/context-char-budget/similarity-threshold/system-prompt/拒答文案，全部环境变量可覆盖）。
14a. `adapter/deepseek/DeepSeekChatModelConfiguration.java` — 补 `@ConditionalOnMissingBean ObservationRegistry` 兜底 bean（真实 deepseek 测试上下文缺该 bean，修复启动失败）。

### 新增（test，包 `com.zl.d33.rag`）
15. `rag/service/ContextAssemblyServiceTest.java` — 去重、排序、预算边界（恰好等于预算 / 超预算截断 / 空白内容过滤）。
16. `rag/service/GenerationServiceTest.java` — Prompt 构造含注入防护约束、模型异常透传。
17. `rag/RagAnswerableEndToEndTest.java` — 场景 1：文档可答，来源可追溯。
18. `rag/RagIrrelevantQuestionTest.java` — 场景 2：无关问题不拼造答案。
19. `rag/RagEmptyRetrievalTest.java` — 场景 3：NO_HIT + ChatModel 零调用（Mockito verifyNoInteractions 可核验）。
20. `rag/RagNoUsableContextTest.java` — 场景 4：NO_USABLE_CONTEXT 不调模型。
21. `rag/RagGenerationTimeoutTest.java` — 场景 5：模型超时→GENERATION_FAILED，可控超时复现。
22. `rag/RagRetrievalFailureTest.java` — 场景 6：向量库异常→RETRIEVAL_FAILED（与空检索可区分）。
23. `rag/RagInvalidCitationTest.java` — 场景 7：非法 [S99] 被剥离并计数，来源无虚假项。
24. `rag/RagDuplicateChunkTest.java` — 场景 8：重复 Chunk 不重复拼接。
25. `rag/RagBudgetOverrunTest.java` — 场景 9：预算截断且来源映射不丢。
26. `rag/D33RagEndToEndIntegrationTest.java` — 门控（D33_RAG_IT=true）真机端到端：真实导入→真实检索→组装→生成。

## 设计决定
- **新建 RagOrchestrator，不改造 KnowledgeChatOrchestrator**：旧编排器契约（内存检索 + 空检索普通问答兜底 + 无状态枚举）与任务书五组件/五状态要求不兼容；新接口并存、旧路径不破坏，符合任务书 §2.3「优先最小改动」。
- **检索通道**：RetrievalService 接 `DocumentVectorSearchService` 真实 pgvector 链路（同模型同 384 维、cosine `<=>`、HNSW），不沿用 `KnowledgeRetrievalService` 内存 bigram 检索为主路径。
- **预算口径**：UTF-16 字符（`UTF16_CODE_UNIT`，与 chunk.length-unit 既有口径一致），默认 4000，**明确标注为非 Token 估算，不伪称 Token**；截断记录 droppedCount 与 truncated 标志，来源映射不丢（只丢弃未进入上下文的尾部）。
- **去重键**：`vectorId`（稳定 chunkId，向量主键）优先，回落 documentId+chunkIndex；排序按 score 降序→chunkIndex 升序→vectorId 字典序稳定。
- **引用编号**：复用 `CitationRegistry`（RetrievedChunk→KnowledgeChunk 适配后注册）；mock 模式由编排器追加服务端编号行，保证离线闭环可演示且编号必然可回溯。
- **Top-K 与阈值**：默认 Top-5、最大 Top-20（与 search 段一致）；`similarity-threshold: 0.0`（不过滤，阈值待真实分数分布另行决策）。
- **ChatModel 零调用可核验**：RagResponse.generation.invoked=false + Mockito verifyNoInteractions 双重证据；NO_HIT/NO_USABLE_CONTEXT/RETRIEVAL_FAILED 三条分支均不触碰 AiChatService。
- **Prompt Injection 防护**：系统约束位于系统提示词（systemPrompt 字段），检索资料经 CitationRegistry.contextBlock 明确标注为不可信数据；模型输出的未知编号被剥离，来源列表仅由后端注册表组装。
- **配置全外置**：模型名/模式/Top-K/预算/阈值/base-url/api-key 走 `app.ai.*` 环境变量（AI_MODE/AI_MODEL/AI_BASE_URL/AI_API_KEY/RAG_*），禁止硬编码密钥。

## 测试
- 九类必测场景全部通过（离线单测，Mockito mock 检索服务与模型，ContextAssemblyService/RagOrchestrator 真实对象）：
  - RagAnswerableEndToEndTest：通过（SUCCESS，来源 documentId/chunkId 与检索命中一致；mock 模式服务端编号行可回溯）
  - RagIrrelevantQuestionTest：通过（低分命中不强拼答案，模型拒答不引用，sources 为空）
  - RagEmptyRetrievalTest：通过（NO_HIT + verifyNoInteractions(AiChatService) + invoked=false 双重零调用证据）
  - RagNoUsableContextTest：通过（空白内容过滤/预算为 0 → NO_USABLE_CONTEXT，零调用）
  - RagGenerationTimeoutTest：通过（Mockito 可控超时 → GENERATION_FAILED，稳定复现）
  - RagRetrievalFailureTest：通过（RETRIEVAL_FAILED 与 NO_HIT 可区分，对外文案不泄露堆栈）
  - RagInvalidCitationTest：通过（[S99] 剥离并计数，来源列表无虚假来源）
  - RagDuplicateChunkTest：通过（上下文块不重复拼接，ArgumentCaptor 核验）
  - RagBudgetOverrunTest：通过（40 字符预算截断、来源映射不丢、droppedCount 记录）
- ContextAssemblyServiceTest：7 用例通过（去重/排序/空白过滤/预算恰好等于/超预算截断/预算 0/null 输入）
- GenerationServiceTest：4 用例通过（Prompt 结构系统约束在前、注入防护、异常透传、结果透传）
- 全量 `mvn clean test`：**Tests run: 33, Failures: 0, Errors: 0, Skipped: 4**，BUILD SUCCESS（Skipped 4 = 门控真机测试待激活）
- 真机门控 `-Dtest=D33RagEndToEndIntegrationTest`（D33_RAG_IT=true，真实 PG/MySQL，AI_MODE=mock）：
  **Tests run: 4, Failures: 0, Errors: 0, Skipped: 0**——真实导入/入库/检索/HTTP 接口/NO_HIT 拒答全部通过
- 真实 DeepSeek 门控（AI_MODE=deepseek，mocked=false）：
  **Tests run: 4, Failures: 0, Errors: 0, Skipped: 0**——真实 ChatModel 调用仅 2 次（最小必要），
  真实回答带合法 [S1] 引用、MODEL_CALL 记录 totalTokens=452、outcome=SUCCESS；NO_HIT 零调用分支仍成立
- 环境修复：两张文档表 18:21 被外部重建为旧结构（0 行），经授权 DROP 后按仓库 DDL 重建对齐（MysqlRebuildTables.java）
- 全量回归（表重建与配置修改后）：`mvn clean test` 仍 **33/0/0/4 BUILD SUCCESS**

## 证据
- docs/evidence/D33/00-env-probe.txt（阶段一）
- docs/evidence/D33/03-mysql-table-columns.txt、04-mysql-four-tables.txt、05-row-count.txt、06-rebuild-tables.txt（表结构核查与重建）
- logs/D33/D33-clean-test.log（全量 33 tests 摘要）、logs/D33/D33-rag-it.log（mock 真机门控 4 tests）、
  logs/D33/D33-rag-it-deepseek.log（**真实 DeepSeek 门控 4 tests，mocked=false，token=452**）、logs/D33/D33-compile.log
- docs/temp_docs/D33/demo_rag_e2e.ps1（演示脚本）、docs/evidence/D33/demo-rag-e2e-output.txt（演示留痕）
- 密钥泄露扫描：日志与证据目录均无 sk- 模式匹配（真实值仅存在于环境变量）

## 遗留问题
- 真实 DeepSeek 端到端已补齐（Level 5 达成），后续复跑继续遵守最小调用原则（仅两个可答用例计费）。
- 相似度阈值保持 0.0，待换语义 Embedding 模型后按分数分布重评；预算为 UTF-16 字符估算（非 Token）。
- 环境隔离：运行其他模块前核对其 schema 资源，防止旧 DDL 再次覆盖 D31+ 表结构。

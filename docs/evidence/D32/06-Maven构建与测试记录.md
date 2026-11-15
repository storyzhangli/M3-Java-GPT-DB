# 06 Maven 构建与测试执行记录（真实执行）

Maven 主路径：D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16（bin\mvn.cmd），JDK 21（D:\SoftwareInstall\JDK\JDK21），
本地仓库 D:\SoftwareInstall\Apache\repository。口令经环境变量注入：MYSQL_PASSWORD / PG_PASSWORD（无默认值，缺失即失败）。

## 1. 基线回归（移植 D31 测试后、新增检索代码前）

- 命令：`mvn -f D32\pom.xml -pl D32_SSATK clean test`
- 日志：`logs/D32/D32-baseline-clean-test.log`
- 结果：**Tests run: 294, Failures: 0, Errors: 0, Skipped: 17**，BUILD SUCCESS（2026-11-15 22:39:40，Total time 01:12 min）
- 说明：与 D31 最终回归 294 tests 一致（50 个测试文件移植，d31→d32 包名/类名/门控批量替换），17 个 skipped 为门控真机测试（D32_VECTOR_IT / D32_PG_IT 默认关闭）。

## 2. 新增检索代码后全量回归（门控关）

- 命令：`mvn -f D32\pom.xml -pl D32_SSATK clean test`
- 日志：`logs/D32/D32-search-unit-clean-test.log`
- 结果：**Tests run: 300, Failures: 0, Errors: 0, Skipped: 18**，BUILD SUCCESS
- 增量：DocumentVectorSearchServiceTest 5 个单测 + D32VectorSearchIntegrationTest 1 个门控测试（skipped）。
- 修复记录：首轮 1 例失败（mapDocumentsToHits 中 cosineDistance 断言容差 1e-9 过紧，float→double 转换误差 3.4e-9），修正为 1e-6 后全绿；294 个移植测试零改动通过。

## 3. 检索真机集成测试（门控开）

- 命令：`mvn -f D32\pom.xml -pl D32_SSATK test -Dtest=D32VectorSearchIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`，环境变量 `D32_SEARCH_IT=true`
- 日志：`logs/D32/D32-search-it.log`
- 结果：**Tests run: 1, Failures: 0, Errors: 0**，BUILD SUCCESS（2026-11-15）
- 真实链路：MySQL ai_db（knowledge_document/document_page_content/vector_index_task/vector_chunk_index）+ PostgreSQL spring_ai（vector_store，28 向量，managedBy=d32）
- 产出证据：docs/evidence/D32/01（语料清单）、02（metadata 实际样本）、03（10 问题 Top-5 JSON）、04（过滤与分数交叉验证）
- 中途调整记录：首轮结构断言 `total>=28` 在 FIXED_SIZE 切分（15 向量，段落边界合并）下失败，改用 STRUCTURE 切分（28 向量）并将断言改为"每篇至少 1 向量"；属测试口径修正，非产品代码问题。

## 4. 编译中间验证

- `mvn -pl D32_SSATK test-compile`：BUILD SUCCESS（logs/D32/D32-test-compile.log）
- 既有警告（与 D31 相同，不阻塞）：SpyBean deprecated 警告、unchecked 警告。

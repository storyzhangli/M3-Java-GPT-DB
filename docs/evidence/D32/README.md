# D32 相似度检索与 Top-K 证据目录

生成时间：2026-11-15。证据全部来自 D32_SSATK 模块的真实 Maven 构建与真机（MySQL ai_db + PostgreSQL 18.7/pgvector 0.8.7 @192.168.200.153）执行，无虚构数据。

| 编号 | 文件 | 内容 | 来源 |
|---|---|---|---|
| 01 | 01-语料入库与索引清单.md | 8 篇设备故障文档的 documentId、入库向量数（28）、导入链路说明 | D32VectorSearchIntegrationTest 真实导入 |
| 02 | 02-vector-store-metadata-samples.txt | vector_store 实际 metadata 样本 3 条（title/source/chunkId/version/strategy/managedBy=d32/documentId/…）与计数 | SELECT metadata::text 真实查询 |
| 03 | 03-top5-retrieval-evaluation.md | 10 个真实业务问题逐条 Top-5 完整 JSON（content/分数/metadata），检索质量标注的数据底座 | DocumentVectorSearchService.search 真实执行 |
| 04 | 04-filter-and-score-check.txt | documentId 过滤命中验证、不存在 documentId 零命中、score=1-distance 与自算余弦交叉验证（误差 9.3e-9） | 真机执行 |
| 05 | 05-人工标注与评估分析.md | 10 问题 × Top-5 逐条人工标注（R/P/N）、汇总指标（Top-1=100%、MRR=1.0、P@5=0.38）、噪声/漏召回原因分析、阈值建议 | 基于 03 逐条对照原文标注 |
| 06 | 06-Maven构建与测试记录.md | 三轮真实构建：基线 294 tests → 全量 300 tests → 门控真机检索测试 | logs/D32/*.log |
| 07 | 07-Git状态记录.md | D32 工作区 git 状态（未授权不提交） | git status |

对应构建日志（logs/D32/）：
- D32-baseline-clean-test.log（294 tests 基线）
- D32-search-unit-clean-test.log（300 tests 全量）
- D32-search-it.log（真机检索集成测试）
- D32-test-compile.log（编译中间验证）

字节码核验证据（docs/temp_docs/D32/，不删除、供复核）：
- PgVectorStore-118-doSimilaritySearch.txt / PgDocumentRowMapper-118.txt：score=1.0-distance 字节码铁证
- PgDistanceType-118.txt：COSINE_DISTANCE `<=>` / vector_cosine_ops / SQL 模板
- PgVectorFilterExpressionConverter-118.txt：metadata jsonb 过滤转换器
- SearchRequest-118.txt：SearchRequest/Builder API 签名

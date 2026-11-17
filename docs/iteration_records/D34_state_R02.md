# D34 迭代状态记录 R02（最终）

- 日期：2026-11-17
- 基线 HEAD：`21965ab12ff3cbbb8c77f3ed54fba551115cc702`
- 状态：代码、离线测试、真机四类风险、12 条评测与双文档交付完成。
- 最终等级：**Level 5 通过**，依据为真实执行结果，不由 Mock 替代。

## 1. 最终验证

1. `mvn clean test`：240 个主源文件编译成功；Tests run 45，44 通过，0 失败，0 错误，1 个真机门控跳过；BUILD SUCCESS。
2. `D34RagRealEndToEndIntegrationTest`：独立设置 `D34_RAG_IT=true`、`AI_MODE=deepseek` 后 1/1 通过。
3. 真实 pgvector：0.8.7、384 维、COSINE、HNSW；新增 3 条独立 fixture，历史数据未删除或改写。
4. 实际 ChatModel：`deepseek-flash`，注入场景 `generationInvoked=true`、`generationMocked=false`；其余三个门禁场景模型零调用。
5. 12 条拒答评测：12/12 通过，七项指标已计算并保留公式、分子与分母。
6. Git 范围：本轮写入仅位于 D34 模块与 docs/logs；原任务书附件保持独立，不计为本轮代码成果。最终复核另发现两处 D33 工作区差异（DeepSeek 观测 Bean 兜底、D33 真机测试注释/断言），它们不是本轮工具写入，已保持原样且排除在 D34 成果之外。`git diff --check` 退出码为 0，仅有换行格式提示。

## 2. 真机结果

| 场景 | decision/status | reasonCode | generation |
|---|---|---|---|
| 无证据 | REFUSE / NO_HIT | NO_EVIDENCE | invoked=false |
| 低相关 | REFUSE / EVIDENCE_REFUSED | LOW_RELEVANCE | invoked=false |
| ACTIVE 冲突 | CONFLICT / EVIDENCE_CONFLICT | EVIDENCE_CONFLICT | invoked=false |
| 文档注入 | REFUSE / GENERATION_VALIDATION_FAILED | UNSUPPORTED_CLAIM | invoked=true, mocked=false |

真机注入模型输出经生成后校验识别 6 条无支持声明，最终只返回受控文案和空来源列表。详见 `docs/evidence/D34/real-e2e-results.json`。

## 3. 真机缺陷修正

首次真机启动因 `ObservationRegistry` 无 Bean 失败，未执行 fixture 写入或模型调用。`DeepSeekChatModelConfiguration` 已改为 `ObjectProvider` + NOOP 回退，第二次真机构建与四场景全部通过。首次失败日志保留，不抹除历史。

## 4. 交付物

- 代码：`D34/D34_ABONEARTAWE/src/main` 与 `src/test`。
- 全量操作文档：`docs/proj_docs/D34-01-基于证据回答与无证据拒答-项目迭代全量信息与操作.md`。
- 技术实践文档：`docs/proj_docs/D34-02-基于证据回答与无证据拒答-技术文档说明与实践指南.md`。
- 证据：`docs/evidence/D34/`。
- 日志：`logs/D34/`。
- 临时脚本与设计：`docs/temp_docs/D34/`，保留未删。

## 5. 明确边界与后续事项

- MySQL 增量 DDL `migration-d34-document-lifecycle.sql` 已创建但未对数据库执行；部署到缺少列的既有 MySQL 前须审核并应用，不能声称迁移已验收。
- 当前工程没有用户身份与 ACL 数据合同，生命周期过滤不等于权限过滤。
- 词面覆盖、动作匹配和事实支持校验是确定性安全门禁，不是完整自然语言推理；阈值需用更大真实数据集继续评估。
- 真机 fixture 按禁止删除历史数据的要求保留；重复执行真机测试会新增独立 UUID 记录。
- 既有弃用警告与 Windows 控制台中文乱码未影响退出码；JSON/Markdown 证据以 UTF-8 保存。

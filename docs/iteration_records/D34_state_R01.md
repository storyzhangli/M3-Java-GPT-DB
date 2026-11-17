# D34 迭代状态记录 R01

- 日期：2026-11-17
- 模块：`D34/D34_ABONEARTAWE`
- 基线 HEAD：`21965ab12ff3cbbb8c77f3ed54fba551115cc702`
- 状态：Level 1–4 已有本机离线证据；Level 5 真机验收已启动，尚未形成最终结论。

## 1. 本轮范围

在 D33「检索 → 上下文组装 → 生成 → 引用」链路上增量加入证据评估、生命周期过滤、澄清/拒答/冲突决策、Grounding Prompt、引用合法性与事实支持校验。不重做 D31–D33，不新增 HTTP 端点。

明确边界：当前工程无用户身份与 ACL 数据合同，本轮不声称完成权限过滤；`minimum-query-coverage` 等阈值是确定性离线门禁参数，不是生产语义理解承诺。

## 2. 已落地实现

- `EvidenceAssessmentService`：生命周期/有效期 → 型号与故障码 → 查询动作覆盖 → 中文二字词面覆盖 → 澄清 → 同范围事实冲突。
- 评估结果包含九部分：`decision/reasonCode/reason/evidenceIds/acceptedEvidenceIds/rejectedEvidenceIds/conflictGroups/missingInformation/assessmentDetails`。
- 文档生命周期：`PENDING/ACTIVE/DEPRECATED`，与导入状态分离；历史 metadata 缺省时由 `legacy-lifecycle-status=ACTIVE` 显式兼容。
- 只有 `ANSWER` 进入生成；拒答、澄清、冲突和空上下文均保留 `generation.invoked=false`。
- `RagProperties.systemPrompt` 与既有 `CitationRegistry` 扩展三区分、不可信资料声明和 `UNTRUSTED_EVIDENCE` 数据块。
- 生成后分别校验引用格式/编号、数值单位、故障码、维修动作、词面支持及提示词/密钥恶意输出；校验失败返回受控文案，不回显模型原始设备事实。
- mock 模式不再回显系统 Prompt 或检索正文，只输出带合法引用的受控证据摘要。

## 3. 已执行验证

| 验证 | 真实结果 | 日志 |
|---|---|---|
| 主代码编译 | 240 个源文件，`BUILD SUCCESS` | `logs/D34/mvn-compile-D34-R01.log` |
| 证据/引用/编排/注入专项 | 38/38 通过 | `logs/D34/mvn-focused-tests-D34-R01.log` |
| 首轮全部离线测试（含 MockMvc 与 12 条评测执行器） | 43/43 通过，0 失败，0 错误 | `logs/D34/mvn-all-tests-D34-R02.log` |
| MockMvc | 4/4 通过：可答新合同、空命中零调用、ACTIVE 冲突零调用、空查询兼容 | 同上 |
| 12 条拒答评测 | 12/12 通过，实际行为已回填 | `docs/evidence/D34/refusal-evaluation-dataset.json` |
| pgvector 只读核验 | 扩展 0.8.7、384 维、既有 2 条 D33 向量；未改写 | `logs/D34/pgvector-readonly-D34-R01.log` |

### 3.1 离线七项指标

来源：`docs/evidence/D34/refusal-evaluation-metrics.json`，执行模式明确为 `OFFLINE_MOCKITO_NOT_REAL_E2E`。

| 指标 | 分子/分母 | 值 |
|---|---:|---:|
| 无证据拒答准确率 | 3/3 | 100% |
| 低相关误答率 | 0/2 | 0% |
| 有证据过度拒答率 | 0/3 | 0% |
| 冲突误答率 | 0/2 | 0% |
| 注入成功率 | 0/2 | 0% |
| 引用合法率 | 6/6 | 100% |
| 整体通过率 | 12/12 | 100% |

这些数值只代表当前确定性离线评测集，不能外推为真实模型生产指标。

## 4. 失败案例与设计修正

- **症状复述被误当维修依据**：若资料只说“检查液压泵”，用户问“如何校准/维修”，仅词面重叠可能错误放行。已增加查询动作覆盖检查，动作未覆盖返回 `INSUFFICIENT_EVIDENCE`。
- **故障码只比较非空集合不充分**：查询显式故障码时，证据必须包含同码；证据无故障码也拒绝。
- **合法引用不等于事实支持**：`[S1]` 合法但 `0.8 MPa` 不在证据中，返回 `UNSUPPORTED_CLAIM`。
- **恶意模型输出可能带合法引用**：补充提示词/密钥恶意输出拦截，合法 `[S1]` 不能授权泄露或复述注入内容。

## 5. 待完成

1. 读取真实 pgvector + 实际 ChatModel 四类风险结果，失败则按真实原因修正或标注阻塞。
2. 执行最终 `mvn clean test`，确认默认构建中真机测试被显式门控跳过，其余测试全通过。
3. MySQL 增量生命周期 DDL 当前只创建未应用；不得声称数据库迁移已验收。
4. 完成失败案例分析、证据索引、最终状态记录和两份 Markdown 技术文档。
5. 复核 Git diff/status，区分本轮代码、用户任务书附件、证据与日志；测试报告不得提交 Git。

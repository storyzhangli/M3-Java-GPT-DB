# D34 证据索引

## 离线证据

| 文件 | 证明内容 |
|---|---|
| `00-env-probe.txt` | JDK/Maven/依赖与环境探测；密钥只记录存在性 |
| `refusal-evaluation-dataset.json` | 12 条用例的问题、证据 fixture、预期与实际行为、引用、通过状态、失败原因 |
| `refusal-evaluation-metrics.json` | 七项指标的公式、分子、分母和值；明确离线模式 |
| `risk-scenario-results.json` | 四类风险离线实际行为与原因码 |
| `failure-analysis.md` | 反例、真机首次失败根因、修正与剩余边界 |
| `test-summary.md` | 最终测试数量、Level 1–5 和复现命令 |
| `git-change-summary.txt` | 最终 Git 状态、diff 检查及两处非本轮 D33 差异披露 |

## 真机证据

| 文件 | 证明内容 |
|---|---|
| `real-e2e-results.json` | 真实 pgvector + 实际 ChatModel 四场景；`level5Passed=true` |
| `../../logs/D34/pgvector-readonly-D34-R01.log` | pgvector 0.8.7、384 维、既有数据只读核验 |
| `../../logs/D34/mvn-real-e2e-D34-R01.log` | 首次启动失败及 ObservationRegistry 根因；未执行 fixture/模型 |
| `../../logs/D34/mvn-real-e2e-D34-R02.log` | 修正后真机 BUILD SUCCESS，实际模型调用与非 mock 证据 |

## 构建日志

| 文件 | 证明内容 |
|---|---|
| `../../logs/D34/mvn-effective-pom.txt` | effective-pom 构建成功 |
| `../../logs/D34/mvn-dependency-tree.txt` | 依赖树构建成功 |
| `../../logs/D34/mvn-compile-D34-R01.log` | 240 源文件编译成功 |
| `../../logs/D34/mvn-focused-tests-D34-R01.log` | 38 项首轮专项测试通过 |
| `../../logs/D34/mvn-all-tests-D34-R02.log` | 43 项阶段测试通过 |
| `../../logs/D34/mvn-clean-test-D34-final.log` | 最终干净构建：45 项、44 通过、1 门控跳过、0 失败、0 错误 |

## 迭代与设计记录

- `../iteration_records/D34_state_R01.md`：阶段状态与离线指标。
- `../iteration_records/D34_state_R02.md`：最终状态与 Level 5 结论。
- `../temp_docs/D34/design-baseline.md`：公开合同、排除项与高风险反例。
- `../temp_docs/D34/probe_d34.ps1`：只读探测脚本，按任务书保留。

## 安全说明

- 证据与日志不包含明文 API Key 或数据库密码。
- `target/surefire-reports/` 属于构建产物，不作为交付物、不提交 Git。
- `real-e2e-results.json` 只保留受控答案与结构化结果，不保存真实模型原始恶意输出。

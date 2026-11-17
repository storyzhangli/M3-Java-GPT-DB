# D34 失败案例与修正记录

## 结论

最终离线评测 12/12 通过，默认 `mvn clean test` 为 44 项通过、1 项显式真机门控跳过；真机测试另行执行 1/1 通过，覆盖四个真实风险场景。当前没有未解决的测试失败。

## 1. 症状复述可能误放行维修问题

- **风险实现**：只用中文二字词面覆盖率时，“液压机压力不足”与“如何校准溢流阀”可能有较高重叠，但资料没有校准步骤。
- **区分性反例**：资料仅包含“检查液压泵输出与溢流阀设定值”，用户询问“如何校准溢流阀”。
- **修正**：增加查询动作覆盖检查；更换、拆卸、维修、调整、校准等动作必须在证据中出现。动作未覆盖返回 `INSUFFICIENT_EVIDENCE`，模型零调用。
- **证据**：`EvidenceAssessmentServiceTest.symptomWithoutRequestedRepairActionIsInsufficient`、评测 D34-006。

## 2. 故障码不匹配不能仅因词面相似放行

- **风险实现**：只在查询与证据都含故障码时比较，证据无故障码可能漏过。
- **修正**：查询包含显式故障码时，证据必须包含同一个规范化故障码；无码或异码均拒绝。
- **证据**：D34-005 实际为 `REFUSE / INSUFFICIENT_EVIDENCE`。

## 3. 合法引用不等于事实受支持

- **反例**：`[S1]` 是合法编号，但答案写 `0.8 MPa`，证据只有 `0.6 MPa`。
- **修正**：分阶段校验编号/格式与事实支持；数值单位、故障码、维修动作和词面支持分别检查。
- **结果**：返回 `UNSUPPORTED_CLAIM` 和受控文案，不回显模型原始设备事实。
- **证据**：`CitationRegistryTest.legalCitationDoesNotProveNumericFactSupport`。

## 4. 真机首次启动缺少 ObservationRegistry

- **日志**：`logs/D34/mvn-real-e2e-D34-R01.log`。
- **真实结果**：ApplicationContext 启动失败，`NoSuchBeanDefinitionException: ObservationRegistry`；未进入测试方法，未写入 fixture，未调用模型。
- **根因**：`DeepSeekChatModelConfiguration` 强制注入观测注册表，而工程未启用 Actuator。
- **修正**：改为 `ObjectProvider<ObservationRegistry>`，缺失时使用 `ObservationRegistry.NOOP`；不增加全局 Bean，不改变 mock 路径。
- **回归**：`logs/D34/mvn-real-e2e-D34-R02.log` 为 `BUILD SUCCESS`。

## 5. 真实模型在注入资料下产生无支持声明

- **真实现象**：注入场景实际调用 `deepseek-flash`，生成 413 个字符；生成后校验识别 6 条无支持声明。
- **处置**：返回 `GENERATION_VALIDATION_FAILED / UNSUPPORTED_CLAIM` 和受控文案，`sources=[]`，未回显虚构参数、非法引用或恶意指令。
- **判断**：这是多层安全按预期拦截，不是测试失败。结果见 `real-e2e-results.json` 的 `REAL-INJECTION`。

## 6. 非阻塞警告与边界

- Maven 控制台存在既有弃用警告和中文编码乱码，但编译与测试退出码均为 0；JSON 证据文件为 UTF-8，可读。
- 默认构建中的真机测试 `Skipped: 1` 是显式门控设计，不代表未验收；其独立真机执行为 1/1 通过。
- 当前无用户身份与 ACL 数据合同，未声称完成权限过滤。
- MySQL 生命周期增量 DDL 已创建但未应用，本轮真机验收只新增 pgvector 隔离 fixture，未执行 MySQL 结构迁移。
- 12 条指标来自确定性离线数据集，不能外推为生产环境语义理解准确率。

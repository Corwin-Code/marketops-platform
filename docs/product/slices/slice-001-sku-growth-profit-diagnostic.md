# Slice 001 — SKU 增长与利润诊断闭环

## 目标

运营人员针对选定 SKU（Ozon / Wildberries）回答：

- 曝光、CTR、转化、价格、促销、库存、退货、广告、Contribution Profit 变化了什么；
- 哪些数据是最新、估算、缺失或低置信度；
- 最可能的根因与其他竞争解释；
- 先做什么动作（证据、预期影响、风险）；
- 改价是否被平台接受、Readback 一致、之后是否改善结果。

首个受控写操作是 `PRICE_CHANGE`。结构化 UI 覆盖完整流程；AI 只做补充，不是第二个事实来源。

## 用户与角色

- 运营：看优先队列与 SKU 诊断，发起建议 / 任务。
- 审批人：批准 / 拒绝改价（需近期重新认证 step-up）。
- 策略管理者：维护 Commercial Policy。
- 管理员：组织 / 店铺 / 凭据元数据、Kill Switch、命令人工处置。
- 外部 OIDC + 强制 MFA；授权 = Role + Action Scope + Store / Platform 范围，由后端 `identityaccess` 执行。

## 核心流程

```text
登录 → 采集 Ozon/WB/内部数据 → 不可变 Raw → 映射 + 版本化指标
→ 质量/新鲜度/置信度 → 确定性诊断 → AI 解释 → 审批 或 Policy 授权
→ Guardrail → 幂等 PRICE_CHANGE Command → 平台执行 → Readback
→ Audit + 补偿 + 结果跟踪
```

**依赖的底座**

- 所有权 `Organization → Legal Entity → Marketplace Account → Store`；Warehouse 归属 Legal Entity，与 Store 是可配置的履约关联，平台基数不做假设。
- 凭据只存不透明引用；Capability 注册表状态 `UNKNOWN / UNVERIFIED / VERIFIED`，未验证不可调用；`production_write_enabled` 默认 false。
- 采集：Schedule / Manual 触发、lease + fencing、cursor、限流、重试预算、backfill。先持久化并校验 Raw 原始字节（hash、来源 / 采集时间、出处）再推进 cursor；有业务含义的失败响应也保存；Replay 只读已存 Raw，零平台调用；重复不产生重复效果。

## 指标与业务规则

**数据状态**

- 平台不提供的指标为 `NOT_AVAILABLE`，绝不当 0；缺失成本任何环节都不变成 0。
- `ConfidenceState`：`CANONICAL_CONFIRMED`、`CANONICAL_PENDING_SETTLEMENT`、`ESTIMATED_EXPLAINED`、`STALE`、`INCOMPLETE`、`CONFLICTED`、`UNKNOWN`；只有确认级别支持平台写。
- 销售分 `COMPLETED` / `RETAINED` / `SETTLED`；Retained 窗口 7d/14d/30d，默认 30d。
- 金额为 decimal `Money` + 明确币种。

**利润**

```text
Contribution Profit = 净收入 − 货品成本 − 平台费用 − 退货损失 − 广告费 − 可变税费
Break-even = 单位成本 + 费用组件(price)
Minimum    = 单位成本 + Required Profit/单位 + Safety Buffer/单位 + 费用组件(price)
```

- 分 Operational / Settled 口径及 `CONTRIBUTION_MARGIN`。
- 历史费用按 `FeeFamily` 覆盖：`PRESENT_NONZERO`、`PRESENT_EXPLICIT_ZERO`、`VERIFIED_NOT_APPLICABLE`、`MISSING_OR_INCOMPLETE`；必需族缺失则不出精确利润。
- 目标价由 `PriceEconomicsCalculator` 按 `PriceEconomicsProfile`（平台 / 店铺 / 履约模式 / 币种 / 有效期；固定、比例、阶梯组件）求解，不平均历史费用。

**诊断**：`DiagnosisEngine` 纯函数，9 条规则固定顺序：`DATA_BLOCKED`（映射未决 / 输入缺失 / 过期，阻断后续）、`NEGATIVE_MARGIN`、`STOCKOUT_RISK`、`HIGH_RETURN`、`LOW_IMPRESSION`、`LOW_CLICK_THROUGH`、`LOW_CONVERSION`、`ADVERTISING_INEFFICIENT`、`PRICE_BELOW_MINIMUM`。缺输入时明确拒绝判断。

**建议**

- `DRAFT → VALIDATED → READY_FOR_REVIEW → APPROVED | POLICY_AUTHORIZED | REJECTED | EXPIRED | CANCELLED → COMMAND_CREATED → EXECUTION_TRACKING → OUTCOME_OBSERVATION → CLOSED`；无写能力为 `TASK_ONLY`。
- 保存依据值摘要（目标价、履约模式、经济性 profile、数据水位），审批、建命令、worker 领取时重新比对，变化即拒绝。审批与 Policy 授权二选一。

**Policy 与 Guardrail**

- 策略版本化，作用域 `ORGANIZATION / PLATFORM / STORE / PRODUCT_VARIANT`，目标 `HERO / GROWTH / MATURE / REPAIR / EXIT`。
- 限额：数据完整度、输入时效、最低毛利率 / 单位利润、单次 / 每日调价幅度、冷却期、最低库存、Policy 授权最大幅度。
- `GuardrailEngine` 纯函数，另查 Break-even / Minimum、映射冲突、诊断阻断、版本变化、建议过期、币种、8 类数据水位（PRICE、STOCK、SALES、RETURNS、FINANCE_FEES、ADVERTISING、INTERNAL_COST、COMMERCIAL_INPUTS）。缺策略或未知一律拒绝。

**`PRICE_CHANGE` 命令**

- 参数 `targetPrice` 必填、`fulfillmentModeCode` 可选，禁止其他字段；先 Impact Preview，创建命令不调用平台。
- `PENDING → LEASED → EXECUTING → PLATFORM_PENDING → READBACK_PENDING → SUCCEEDED`；异常：`RETRY_WAIT`、`UNKNOWN_REQUIRES_READBACK`、`READBACK_MISMATCH`、`MANUAL_RESOLUTION`、`FAILED_FINAL`、`COMPENSATION_PENDING → COMPENSATED | COMPENSATION_FAILED`。
- 仅 Readback 一致算成功；写超时 = 未知，先查询不重试；429 可重试。
- 补偿仅当平台仍是本命令写入值，不覆盖后续合法变更。
- 状态迁移由数据库函数强制；worker 领取事务内检查 Kill Switch（全局 / 平台 / 账户 / 店铺）与试点 allowlist。

## 数据与平台接口

- 模块：`marketplaceintegration`（Raw、采集、适配器、价格命令、Kill Switch）、`productlisting`（SKU / Variant / Barcode、平台 listing、版本化映射与冲突队列）、`operatingfacts`（跨域事实、导入）、`analyticsdecision`、`aicopilot`、`operationsworkflow`、`adminobservability`（审计）。
- 平台行为来自注册表数据（URL、认证头、分页、限流、写结果模型、异步句柄、价格读取位置），每行带验证状态；Ozon 与 WB 分别实现，不假设对称；只用官方 API / 报表。
- 内部数据：Excel/CSV 导入与手工录入（`PURCHASE_COST`、`INTERNAL_STOCK`、`FINANCE_INPUT`），含 hash、预览、校验、拒绝报告、重放，版本化不覆盖。
- 大量输出走异步导出；部署目标 Yandex Cloud `ru-central1`。

## AI 的使用

- `AiCopilot` 经供应商中立网关；投影为字段白名单，排除 Buyer PII 与 Secret。
- 输出分 `FACT / INFERENCE / RECOMMENDATION / UNKNOWN`；FACT 须引用已展示的 Metric / Evidence，否则拒绝；指令式文本也拒绝。
- 给出根因、竞争假设、建议、优先级、影响、风险、验证周期。
- AI 不能创建 / 审批 / 执行命令，不覆盖指标；模型不可用只降级解释。

## 已实现 / 未实现

**已实现**

- 后端上述模块及对应数据表：身份授权、映射、事实、导入、指标、诊断、AI 声明、审批、Guardrail、价格命令、注册表、导出、经济性 profile。
- 前端 `marketops-console`：OIDC + PKCE 登录（token 仅存内存）、`PriorityQueue`、`SubjectDiagnosisView`、`MetricEvidencePanel`、`AiExplanationPanel`、`RecommendationReview`、`CommandTimeline`、`DiagnosticExportPanel`；过期 / 未知 / 不一致不显示为成功。
- S3 兼容与本地文件对象存储。

**未实现 / 未验证**

- 未接入真实 Ozon / Wildberries：能力行均为 `UNVERIFIED`，仅合成适配器与 fixtures，无真实调用或改价。
- LLM 已接入阿里云百炼 `qwen3.8-max`（本地登记方式见 `docs/development.md`）。
- 真实 OIDC / MFA、Yandex 部署与恢复、告警、试点范围未完成；生产写入关闭。
- 范围外：库存 / 广告 / 促销 / 内容 / 订单生产写入、财税、多租户、AI 自主执行。

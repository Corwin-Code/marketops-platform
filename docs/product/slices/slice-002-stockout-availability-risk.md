# Slice 002：缺货与可售风险及责任闭环

## 目标

- 防止盈利 SKU 断货：计算并排序风险，转成有负责人的 Task，自动验证是否改善，未改善则重开同一 Case。
- 区分两类独立风险：**Channel**（某个 listing/履约模式不可售，仓库可能有货）与 **Company**（公司整体货源即将耗尽）。
- 只做 Queue + Task，不写任何平台。

## 用户与角色

| 职责 | 角色 |
| --- | --- |
| 渠道可售恢复 | `MARKETPLACE_OPERATOR` |
| 补货评估、在途证明、lead-time/safety 策略 | `PRODUCT_PROCUREMENT` |
| 库存/Mapping/归属/数据源缺陷 | `TECH_DATA` |
| 利润/成本数据阻塞 | `FINANCE_ANALYST` |
| HIGH 例外审批 | `OPS_LEAD` 或有效期内的委托人 |
| CRITICAL/重复/重大例外审批 | Owner 指定的 Risk Authority |
| 只读审计 | `AUDITOR` |

所有读写受 Organization 及 Platform/Store/Product/Data/Action Scope 约束，前端可见不等于有权限。

## 核心流程

1. **风险识别**：事实入库（库存、可售、销售、退货、在途、策略、利润）→ 定向重算受影响 Variant；每小时全量对账用同一计算器，结果一致，并补上漏掉的触发。
2. **队列**：每个 `Organization + Internal Product Variant` 一张卡；子风险分为 Channel（`Platform + Store + Listing Variant + Fulfillment Mode`）和 Company（`Organization + Variant`），各自独立计算、独立清除。卡片取最严重子风险的 lane，并标明是哪个子风险触发。
3. **Case/Task**：每个 `RiskCause` 绑定责任角色，按 cause 路由（不是看的人）。同一活跃 cause 只有一个 Case（数据库唯一索引保证并发/重放不重复）；不同 owner 的独立 cause 可各自建 Task。
   - CRITICAL：当轮自动建/更新 Task
   - HIGH：持续多轮后才建 Task
   - WATCH：只在队列可见
   - 数据/策略/利润/质量阻塞：首次出现即建对应修复 Task
4. **两阶段关闭**：阶段 1 记录结构化动作 + 证据（`CaseActionKind`，如 `INBOUND_EVIDENCE_BOUND`、`DATA_OR_MAPPING_REPAIR`），纯文本确认会被拒绝，状态进入 `VERIFYING`。阶段 2 由重算自动判断 cause 是否修复且在规定窗口内持续修复 → `VERIFIED_SUCCESS`；回退则同一 Case `REOPENED`/`ESCALATED`；到期仍未修复 → `REWORK_REQUIRED`。Action SLA 与 Outcome SLA 分开记录。
5. **例外（Accepted Exception）**：只是业务处置，不改变计算出的 lane，也不会产生 `VERIFIED_SUCCESS`。须有证据、理由、范围、审批人和有效期。审批按 materiality 分级（`DOMAIN_LEAD` / `OPS_LEAD` / `RISK_AUTHORITY`）；CRITICAL/重复/重大情况下申请人不能是唯一审批人；无有效 materiality 配置 → `AUTHORITY_BLOCKED`，原风险保持活跃。到期或条件变化时自动失效并重开同一 Case，不可编辑延期。

## 风险计算与业务规则

- **公司供给**：只计公司自有、物理上独立、当前可用、已去重的库存；扣除预留、QC 锁定、损坏/报废、不可售。平台侧库存需要 supply ownership declaration：`MIRRORS_INTERNAL`（同一批货，不可相加）或 `PHYSICALLY_DISTINCT`（可相加）；未声明/冲突 → `UNKNOWN`，Company 不可能得出安全结论。
- **在途**：必须由 Procurement 角色绑定证据；只在到货窗口的最晚端计入，永远不算当前在手。Draft/估计可见但不减风险；过期、取消、逾期、冲突的在途立即失效并触发重算。
- **Lead time / safety**：版本化、按生效期管理，按 Variant+Supplier+Route → Supplier/Category → Organization 默认 依次回退；都没有 → `POLICY_BLOCKED`，不会当作 0。
- **需求**：以 Completed 销售为主，基于 D7/D14/D30 的确定性、版本化策略。缺货或下架期间属于删失窗口，不能压低需求；全部删失时可在有限期内沿用上一个有效值（降级显示），到期 → `DATA_BLOCKED`，需求从不置 0，也不用模型推算。
- **退货质量**：Retained/退货/拒收/QC 是独立的质量 Guardrail，不用"销售 − 退货 = 需求"。退货只有在 return ledger 到达 `REENTERED_AVAILABLE`（`RESELLABLE`）并被后续仓库快照关联后才算供给。
- **利润资格**：Settled 利润为正 → confirmed；无 Settled 时 Operational 为正 → operational；仅估算为正 → provisional；数据陈旧/不完整 → `PROFIT_DATA_BLOCKED`；为零或负 → 不进主队列。Lifecycle（Hero/Growth）不能覆盖负利润。
- **优先级**：数据缺陷走 Review/Blocked；即将断货的紧急情况一律 CRITICAL，不会被商业评分压下去。其余按可见因子（断货天数、风险利润、销售速度、lifecycle、置信度惩罚）确定性排序，UI 展示每个因子及策略版本。
- **非对称安全**：新鲜的 Channel 事实（如 Ozon FBO 可用 = 0）单独即可行动，不受无关数据源陈旧影响。Company 失败即关闭：只要缺少关键输入，已有证据能证明危险时输出 `PROVISIONAL` + 可复算的 conservative proof，否则输出 `UNRESOLVED` + `DATA_BLOCKED`，绝不显示 `HEALTHY`。
- **状态**：lane（`HEALTHY/WATCH/HIGH/CRITICAL/REVIEW/UNRESOLVED`）与证据状态（`CONFIRMED/PROVISIONAL/CARRIED_FORWARD/DATA_BLOCKED/STALE` 等）分开给出，UI 视觉上区分。
- **响应目标**：从 `fact_accepted_at` 起算，CRITICAL P95 ≤ 5 分钟、上限 15 分钟；其他 ≤ 15 分钟；全量对账每 60 分钟。source latency 与内部 latency 分开记录。积压、对账超时、CRITICAL 超时作为命名事件报警。

## 数据与平台接口

- 模块 `availabilityrisk` 只通过已发布接口读取：`OrganizationDirectory`、`ListingIdentityDirectory`、`OperatingFactQuery`/`EvidenceQuery`、`MetricQuery`（利润）、`BusinessAuthorization`；Case 与例外由 `operationsworkflow` 负责，审计写入 `MetadataAuditRecorder`。
- 主要数据：版本化策略（生效期不重叠）、在途证明、风险投影（卡/子风险）、重算队列与 fact-feed cursor、对账记录、SLO 观测、Case、例外；迁移 V0030–V0035。
- Console API（`/api/v1/console/availability`）：`GET /queue`、`GET /cards/{productVariantId}`、`GET|POST /cases…`（action、escalation、exceptions、journal）、`POST /exceptions/{id}/decision`；另有 `/inbound`（证明、修改、取消、复核）和 `/policies`（发布/停用）。权限：`AVAILABILITY_VIEW`、`AVAILABILITY_TASK_ACT`、`AVAILABILITY_EXCEPTION_REQUEST`、`AVAILABILITY_EXCEPTION_APPROVE`（需要近期重新认证）、`INBOUND_ATTEST`、`SUPPLY_POLICY_MANAGE`。没有可直接宣布成功的接口。
- 平台：需要 Ozon/Wildberries 的库存、可售状态、销售和退货读取（FBO/FBS 区分）；目前未接入真实 API，仅用 synthetic/fixture 数据；WB 不假定与 Ozon 对称。

## AI 的使用

- 本 slice 不使用 AI。未来 AI 最多用来解释已算好的卡片；不能计算或覆盖风险、lane、排序、需求、策略、Task、例外或授权。

## 已实现 / 未实现

**已实现**（后端 `availabilityrisk` 共 72 个类）：
- 需求策略引擎、`ChannelRiskCalculator`/`CompanyRiskCalculator`、conservative proof、利润 lane、确定性排序、Task 激活策略
- 定向 worker、每小时对账（keyset 分页、单 Variant 失败隔离）、fact-feed cursor、SLO/健康事件
- Case 生命周期、自动结果验证、例外治理与委托、在途证明与策略管理 API
- 前端 `availability/`：`AvailabilityQueue`、`AvailabilityCases`、`AvailabilityAuthorityPanel`

**未实现 / 不在范围内**：
- 真实 Ozon/WB 调用、真实组织策略取值、生产身份与部署
- 补货数量/下单日期建议、采购执行、WMS 收货
- 呆滞/超储、调拨/分配、清仓、广告干预
- 任何库存写入（`STOCK_CHANGE`）、需求预测或流失需求模型、外部 AI 调用

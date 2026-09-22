# Slice 003：广告与流量效率（Advertising & Traffic Efficiency）

## 目标

- 以原子 Advertising Case 管理 Ozon / Wildberries 原生广告对象：区分官方流量/花费、平台归因与公司真实销售/利润，给出确定性 lane、责任 Task 和受控出价动作。
- 成功标准：受影响 Variant 集合的 30 天 Retained Sales 保持且每个关键销售单元通过；Advertising Contribution Profit（绝对值）与每官方广告 RUB 利润两轴中至少一轴显著改善、另一轴不显著变差。仍为负的利润最多是 `IMPROVED_NOT_HEALTHY`。

## 用户与角色

| 角色 | 职责 |
| --- | --- |
| `MARKETPLACE_OPERATOR` | 审阅 Case，选择/拒绝候选（Maker），执行 Manual Packet，对象级 Hold |
| `OPS_LEAD` | 业务决策负责人；独立背书；业务 Kill；重新启用背书 |
| `OWNER` | 初期每条出价命令的最终批准；Accepted Exception、Bundle 激活、重新启用的最终批准 |
| Finance | 提供/复核财务事实，处理 Settled 矛盾复核 Task |
| `PLATFORM_ADMIN` | 凭证/Provider/Readback 类 Kill 与技术证明 |

提议、背书、批准、执行、验证、Kill、重新启用是分开的权限；前端可见不等于授权。

## 核心流程

1. **Exposure（事实）**：官方 API/报表 → 不可变 Raw → 原生对象 lineage + 完整 affected-set → 流量、花费、销售、转化、利润计算 → 按决策用途判定 Freshness。
2. **Lane 与优先级**：`PROTECTION`（Fresh 单向危险）/ `DATA_REPAIR`（证据缺失或冲突）/ `OPTIMIZATION`（成熟、足量、持续的机会）/ `WATCH`。Protection 分 P0–P3；排序为字典序，不用加权分数替代。
3. **Case 与 Task**：每个独立原因一个 Case，非 Watch 时一个责任 Task；两阶段 SLO（ACKNOWLEDGEMENT → ACTION）。打开页面不算确认；SLO 超时只升级，不授权写。
4. **Candidates**：`AD_BID_TARGET_POLICY` 生成有限确定候选（`BidCandidateSet`），基础为 `MAX_CPC_BOUNDED` 或 `CAUSE_BOUND_PROTECTION_STEP`；Operator 只能选/拒，不能自填数值，adapter 不做舍入。
5. **Approval**：Impact Preview → Maker 选择 → 另一人 `OPS_LEAD` 背书 → 第三人 `OWNER` 批准。批准有 `expires_at`，取所有依赖（lease、证据、policy、凭证）中最早者，不可延长。
6. **Bid command**：一个动作一个 `AD_BID_CHANGE` Command 身份（Outbox + worker），重试不改目标；发送前再查 Kill/Quarantine/Lease/aggregate exposure。
7. **Readback**：HTTP 成功不等于成功，官方当前 Bid 必须精确等于批准目标（`READBACK_MATCHED`）。结果未知 → `UNKNOWN_REQUIRES_READBACK`；无已验证幂等时不盲目重试；出现第三个值 → mismatch 调查，不覆盖。
8. **Outcome**：执行前冻结 `OUTCOME_EVALUATION_PLAN` 与 baseline。观测分 `OPERATIONAL`（早期 Completed-Sales 安全）、`RETAINED`（30 天业务结果，未结算）、`SETTLED`（结算确认，可推翻前者并生成 Finance 复核 Task）。回归 → `ACTION_OUTCOME_QUARANTINE`。

- **Manual Shadow**（两平台）：Manual Execution Packet → 执行人报告 → 另一人或官方证据验证；自报仅为 `ACTION_REPORTED_CONFIGURATION_UNVERIFIED`，Packet 不产生 API 命令。
- **Live Queue / Daily Action Brief / Weekly Evidence Review**：Brief 按 `core.ad_reporting_calendar` 发布，是只读投影；已发布内容不可修改，迟到数据生成新修订并记录 delta（`ADDED`/`REMOVED`/`RESTATED`）。

## 业务规则与安全控制

- `Max CPC = 同阶段 Allowable CPA × 同阶段转化率`，是上限而非下一个出价。可回收价值 `max(0, min(N×C, A) − N×b)`，保留 4 位小数。
- 公司总销售 ÷ 广告点击不是转化；Provider 归因只是观测。缺失指标为 `NOT_AVAILABLE`，不是 0；Unknown 不能产生加价、降价、暂停或成功。
- 加价需完整写级证据；Protection 降价可凭 Fresh 单向危险证据。
- 初期每个非零 `AD_BID_CHANGE` 都是 `MATERIAL_IMPACT`，无常备自动化；Ordinary 路线（Maker → 另一 `OPS_LEAD` 批准）需后续晋升证据。
- **Reservation**：执行中的干预锁定完整 affected-set，重叠动作被阻止；释放需配置已确认、早期 Completed-Sales 观测完成、无回归。
- **Aggregate exposure**：六轴（并发干预、销售占比、花费、累计出价变动、未决写入、恢复预留）各自必须通过，互不抵消。
- 同对象再次出价暂不开放；唯一例外是人工发起的恢复原 Bid Compensation（新 Preview + Maker/Ops/Owner）。禁止自动回滚。
- **Accepted Exception**：限时，需 Ops + Owner 批准，只暂停 Action SLO，不改 lane、不授权写，无自动续期。
- **Kill / Hold / Quarantine**：单向、立即生效、无需第二人批准；已发出的请求继续 readback 对账；旧批准和未执行 Packet 永久失效。种类：`EMERGENCY_ENTITY_HOLD`、`ACTION_OUTCOME_QUARANTINE`、`AUTHORITY_VERSION_QUARANTINE`、`CAPABILITY_QUARANTINED`、`KILL_SWITCH_ACTIVE`。
- **重新启用**：记录根因、Unknown 已解决、权威已替换、结果已对账、能力证据当前（技术原因另需安全证明）→ 非停止者 `OPS_LEAD` 背书 → 不同人 `OWNER` 以新 Bundle 提交。时间流逝不恢复任何旧批准。
- 每个作用域必须有唯一完整激活的 `ADVERTISING_DECISION_POLICY_BUNDLE`，否则写路径 fail closed。
- 金额 decimal + 显式币种；官方修正为追加事实；不用 Buyer PII 关联。事实变化触发 targeted recalculation，每 60 分钟全量对账。
- 披露统一经 `AdvertisingDisclosurePolicy`。

## 数据与平台接口

- 实体：Advertising Case、affected-set、Semantic Profile、Task、候选、Impact Preview、Approval、`AD_BID_CHANGE` Command、Reservation、Containment、Exception、Manual Packet、Outcome、Policy Bundle、Brief。
- 平台：只用 Ozon/WB 官方广告 API/报表（配置、花费、曝光/点击、平台归因订单）。出价写入由数据驱动的 `PlatformHttpAdBidWriteAdapter` 读取已验证 registry，无平台分支。两平台语义不强行对齐，Ozon 证据不授权 WB。
- API：`/api/v1/console/advertising/*`（queue、cases、tasks、exceptions、manual-packets、containments、decision-bundles、briefs）与 `/api/v1/console/workflow/*`（ad-bid-impact-preview、ad-bid-compensations）。

## AI 的使用

- `aicopilot` 可基于授权投影起草解释；`AiDiagnosisService` 可提出 `ADVERTISING_REVIEW` 建议（仅 `reviewFocus`），不授权任何动作。
- AI 不能决定 Metric、lane、优先级、baseline、候选数值、审批、Command、Kill/Quarantine 或 Outcome。

## 已实现 / 未实现

**已实现（代码）**

- `advertisingefficiency`：`AdLaneResolver`、`AdPriorityPolicy`、`AdLinkedConversion`、`AdvertisingContributionProfit`、`AdvertisingPurposeFreshness`；Outcome、Manual workflow、Brief、containment/Bundle 控制、targeted/reconciliation worker。
- `operationsworkflow`：责任 Task 与 SLO、`AdvertisingHumanDecisionService`、`AdvertisingExceptionService`、Compensation。
- `marketplaceintegration`：`AdBidCommandService`/`Worker`/`Scheduler`、`AdBidApprovalAuthorityService`、`AdBidCompensationService`。
- 前端 `src/advertising/`：Queue、CaseView、Workflow、ManualShadow、Containment、Compensation、Brief、OutcomeHistory、CommandTimeline。

**未实现 / 不做**

- 真实 Ozon/WB 广告 API 未接入，Provider 写路径结构上不可达（`UNVERIFIED`），生产写关闭；仅合成/fixture 数据。真实 LLM 未接入。
- 不做：Budget 变更、Campaign 暂停/恢复、策略切换、结构/关键词/创意写入、自动组合预算、常备自动出价、最优出价预测、因果增量结论、爬虫。
- 待定：Outcome Policy 阈值、SLO profile、同对象再入规则，源码无默认值。

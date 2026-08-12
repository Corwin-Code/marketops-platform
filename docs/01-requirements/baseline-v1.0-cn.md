---
title: "俄罗斯 Marketplace 运营与决策平台"
subtitle: "产品需求、系统需求与开发落地基准（PRD + SRS + Solution Blueprint）"
author: "Owner / Product & Engineering Baseline"
date: "2026-08-06"
lang: zh-CN
---

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

# 目录与阅读路径 {.unnumbered}

## 建议阅读路径

- **业务负责人 / Owner**：先阅读第 1～5、8、12、20、23 章，确认产品方向、经营口径和阶段边界。
- **Product Manager / Business Analyst**：重点阅读第 3～10、12、20、24 章，并将 Requirement ID 拆为 Epic、Story 和验收用例。
- **Tech Lead / Architect**：重点阅读第 5～7、11、13～19、23 章，形成 ADR、API Spec、数据模型和部署方案。
- **Backend / Data Engineer**：重点阅读第 6～9、11～15、18～19 章。
- **Frontend Engineer**：重点阅读第 4、8～10、12、18 章。
- **QA / Security / DevOps**：重点阅读第 5、9、15～19、23 章。
- **运营、仓库与财务 Key User**：重点阅读第 2、4、6、8～10、12、23～24 章。

## 主体章节

1. Executive Summary
2. 业务背景、问题与产品机会
3. 产品目标、非目标和成功标准
4. 用户、角色与责任边界
5. 产品原则与全局硬规则
6. 领域模型、术语与数据可信度
7. 系统上下文与目标架构
8. 端到端业务流程
9. Functional Requirements
10. 页面与交互基准
11. 数据架构与核心模型
12. 指标、利润口径与决策规则
13. Ozon / Wildberries 集成设计基准
14. 内部 API 与工程实现标准
15. Non-functional Requirements
16. 安全、账号、隐私与合规
17. AI Operations Copilot
18. 测试与质量保障
19. DevOps、可观测性与运维
20. 交付路线图与 Sprint 建议
21. 团队组织与治理机制
22. 风险、假设与决策台账
23. 验收与上线 Gate
24. 业务输入清单
25. Appendices

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

# 文档控制 {.unnumbered}

本文件是 **Russia Marketplace Operations & Decision Platform** 的产品与开发统一基线。它同时承担以下作用：

1. Product Vision 与范围约束；
2. PRD：业务问题、用户、流程、功能及验收意图；
3. SRS：系统需求、数据边界、接口、非功能要求和安全要求；
4. Solution Blueprint：推荐架构、模块边界、数据模型和交付路径；
5. Traceability Baseline：Requirement ID、测试、发布和后续变更的稳定引用点。

本文件不是最终 UI Design、数据库 DDL、OpenAPI Contract 或 Runbook。开发团队必须在本基线之下继续产出模块级设计，但不得自行改变本文件已经固定的 Owner 决策和全局硬规则。

| 项目 | 内容 |
| --- | --- |
| 文档名称 | 俄罗斯 Marketplace 运营与决策平台——产品需求、系统需求与开发落地基准 |
| 版本 | v1.0 |
| 状态 | BASELINE FOR PRODUCT DIRECTION & IMPLEMENTATION PLANNING |
| 适用平台 | Ozon + Wildberries；后续通过 Adapter 扩展其他 Marketplace |
| 业务主体 | 俄罗斯本地经营主体、本地仓库及其授权运营团队 |
| 建设策略 | Ozon 端到端优先；Wildberries Read Integration 与统一分析并行 |
| 首版技术建议 | Java / Spring Boot / PostgreSQL / Flyway / React + TypeScript / Modular Monolith |
| 主要读者 | Owner、业务运营、产品、架构、开发、数据、QA、DevOps、安全、财务、仓库 |
| 最后核验日期 | 2026-08-06 |

## 版本与变更治理

- 所有需求变更必须引用 Requirement ID，并登记变更原因、影响模块、数据迁移、兼容性、测试与回滚方式。
- 平台 API、费用、结算和算法规则属于高变化外部依赖；其当前事实应记录在 `Platform API Capability Matrix`，而不是散落在业务代码中。
- 本文件中的商业公式是内部经营基线。平台后台展示值可以作为对照，但不能未经核验直接替代内部口径。
- 本文件中的法律与合规内容是工程控制要求，不替代俄罗斯执业律师、会计师或数据保护负责人的正式意见。

> **Owner 已固定的核心边界**：第一版必须先建立数据真相、经营可见性和可审计的人工决策闭环；平台写操作默认关闭。只有 Recommendation、Shadow Mode、Approval、Guardrail、Write、Readback 和 Audit 全链路通过后，才允许逐能力开放写入。

# Executive Summary

## 产品定义

本产品是一套服务于俄罗斯本地电商经营主体的内部 **Marketplace Operations & Decision Platform**。它统一接入 Ozon、Wildberries、本地仓库、采购成本、广告、促销、退货和财务结算数据，形成可追溯的商品、订单、库存和利润事实，并将异常转化为可执行的运营任务、审批和受控平台操作。

它不是另一个只展示 GMV 的 Dashboard，也不是第一阶段就替代 ERP、WMS 或会计系统。它要解决的是：经营团队无法在同一口径下回答“卖了什么、为什么卖不动、哪里在亏损、库存应如何分配、下一步该做什么、执行后是否有效”。

## 核心价值链

```text
官方平台数据 + 内部采购/仓库/成本事实
                 ↓
不可变 Raw Evidence + 跨平台统一模型
                 ↓
订单 / 库存 / 财务 Ledger 与可重算指标
                 ↓
SKU 漏斗、利润、退货、广告和库存诊断
                 ↓
Recommendation → Task → Approval → Controlled Execution
                 ↓
Readback Verification → Metric Follow-up → Knowledge Accumulation
```

## 第一阶段产品承诺

第一阶段必须让业务团队每天可靠地完成以下动作：

- 查看 Ozon 与 Wildberries 的统一经营总览；
- 下钻到 SPU、SKU、颜色、尺码、平台、仓库和采购批次；
- 识别低曝光、低 CTR、低转化、高退货、负利润、缺货和滞销问题；
- 获得可解释的运营建议和待办任务；
- 对订单、库存、财务与广告数据进行来源追溯和差异核查；
- 计算 Operational Profit 与 Settled Profit，并明确其可信度；
- 在没有自动写平台风险的前提下，为商品、库存、广告和促销决策提供技术支持。

## 首版建设裁定

- **架构覆盖双平台，交付主线 Ozon-first。** Ozon 先完成商品、订单、库存、退货、财务、广告、任务和受控操作闭环；Wildberries 同期完成商品、价格、库存、销售、退货、财务和 Analytics 的读取与统一分析。
- **Read First，Write Later。** 所有写能力由 Feature Flag 关闭；初期只产出 Recommendation 和 Dry Run。
- **Variant-level First。** 服装等多规格业务必须以颜色、尺码和采购批次为最细经营单位。
- **Ledger First。** 库存与财务事实采用不可变流水；Snapshot 只用于快速查询。
- **Simple but Production-grade。** 首版采用 Modular Monolith 和 PostgreSQL Worker，不为未来假设提前引入 Kafka、Kubernetes 和微服务。

# 业务背景、问题与产品机会

## 当前业务条件

经营主体已经具备俄罗斯本地公司、Ozon 与 Wildberries 店铺、本地仓库、发货和退换货能力，并拥有可投入的电商技术团队。当前销量不理想，但问题尚未被统一数据模型分解，运营决策容易依赖平台后台碎片、Excel、人工经验和外部服务商解释。

## 典型业务痛点

### 数据碎片化

Ozon、Wildberries、仓库、采购、广告和财务数据具有不同 ID、状态、时间口径和更新延迟。一个“订单”在平台、仓库和结算中可能对应不同对象；一个“商品”也可能存在 Offer ID、SKU、nmID、Barcode 与内部货号等多重身份。

### GMV 掩盖真实经营结果

销量、广告订单和活动收入并不等于利润。佣金、物流、仓储、退货、折扣、罚款、赔付和晚到费用可能跨周期出现。没有统一 Ledger 和重算机制时，运营团队可能放大一个表面增长、实际亏损的 SKU。

### 运营问题未被转换为行动

传统 BI 告诉团队“CTR 下降了”，但没有明确：证据是否可信、问题由哪一环节引起、谁负责、建议动作是什么、风险和预期影响如何、执行后是否改善。

### 平台自动化风险高

价格、库存、广告预算和促销参与都会直接影响现金、履约和平台风险。若缺少权限隔离、审批、最低利润、库存安全、幂等、Readback 与审计，自动化可能把一个错误快速放大。

### API 与规则持续变化

Marketplace API 的版本、限流、鉴权和字段会持续变化。一次性脚本和把平台 JSON 直接写入核心表的做法，很容易在接口升级后导致静默数据丢失或错误决策。

## 产品机会

本项目的差异化不是复制第三方竞品分析工具，而是建设经营主体自己的第一方数据和执行闭环：

- 平台官方数据作为交易和运营来源；
- 内部仓库、采购和成本作为公司经营真相；
- 第三方市场数据仅作为趋势信号；
- 线下反馈、退货 QC 和供应商批次形成独有数据资产；
- 每次运营实验、审批和执行结果被结构化沉淀；
- IT 团队从“做报表”转变为“建设可复制经营能力”。

# 产品目标、非目标和成功标准

## 产品目标

### G1：建立统一、可追溯的数据事实

任何核心经营数字均能回答：来自哪个平台、哪个账户、哪个接口或报表、何时产生、何时采集、经过什么转换、使用哪个指标版本、是否已结算。

### G2：形成 SKU 级经营诊断

至少支持 Platform → Store → Category → SPU → Variant → Color/Size → Warehouse/Region → Campaign → Purchase Batch 的下钻与对比。

### G3：让异常成为可管理工作

系统不只发 Alert，还应形成 Recommendation、Task、Owner、Due Date、Evidence、Approval 和 Result。

### G4：建立利润和风险 Guardrail

所有价格、促销、广告、库存和补货建议必须接受确定性规则校验；不能由 AI 或单一平台指标绕过最低利润、库存安全和权限边界。

### G5：支持增量自动化

先实现读取、诊断和人工闭环，再以 Capability 为单位逐步开放写操作；每个能力可以独立关闭、回滚和审计。

## 非目标

第一阶段明确不建设：

- 面向外部卖家的通用 SaaS；
- 通过爬虫或浏览器自动化绕过平台公开 API；
- 全量替代成熟会计、税务、ERP 或 WMS；
- 无人工批准的自动降价、加预算、参加促销或大批量补货；
- 同时接入多个次要平台；
- 大规模实时流计算、Kafka、Kubernetes 或微服务平台；
- 将第三方竞品估算当作采购或财务真值；
- 将未经母语审核的 AI 内容直接发布到商品卡。

## 业务成功指标

| 类别 | 首版目标 | 衡量方式 |
| --- | --- | --- |
| 数据可见性 | T+1 统一经营视图稳定生成 | 连续 20 个运营日自动生成且无重大缺口 |
| 商品身份 | 活跃 Variant 映射率 ≥ 99% | 平台活跃商品与内部 SKU 对账 |
| 订单完整性 | Closed Day 订单行完整率 ≥ 99.5% | 平台报表/API 与内部 Core 对比 |
| 利润可解释性 | 重点 SKU 全部具备可追溯利润拆分 | 每个费用可回溯 Raw/Settlement/内部成本 |
| 异常发现 | 重大缺货、负利润、高退货在 1 个运营日内发现 | Alert 首次触发时间与业务确认时间 |
| 运营采用 | 运营团队每天处理系统任务 | 活跃用户、任务完成率、逾期率 |
| 手工工作 | 显著减少人工合并平台报表 | 基线工时与上线后工时对比 |
| 控制风险 | 所有平台写操作 100% 可审计和回读 | Execution Audit 与 Readback 结果 |

## 产品阶段

- **Phase 0：Data, Identity & Visibility Foundation。** 统一账户、商品身份、Raw Layer、历史回填、数据质量和基础日报。
- **Phase 1：Ozon Operational Closure + WB Unified Read。** Ozon 端到端经营分析；WB 读取与统一对比；仍以建议为主。
- **Phase 2：Operations Workbench & Controlled Write。** 仓库、退货、实验、审批、Shadow Mode 和小范围平台写入。
- **Phase 3：Guardrailed Automation & AI Copilot。** 在稳定指标与审计基础上开放 L3～L4 自动化。

阶段不是日期标签，而是 Gate。前一阶段的数据、权限、质量和恢复条件未满足时，不得以进度压力跳过 Gate。

# 用户、角色与责任边界

## 核心用户

| 角色 | 主要目标 | 系统主要能力 |
| --- | --- | --- |
| Owner / General Manager | 掌握真实利润、风险和关键决策 | Command Center、审批、经营报告、审计 |
| Operations Lead | 推动商品、价格、广告、活动和任务闭环 | 诊断、建议、任务、实验、审批 |
| Marketplace Operator | 完成店铺日常运营 | Listing、价格建议、广告、促销、评价、任务 |
| Warehouse Operator | 按 SLA 完成拣货、发货、退货 QC 和盘点 | Warehouse Workbench、库存流水、异常 |
| Finance / Analyst | 对账、结算、利润和差异解释 | Finance Close、Settlement、Profit、Adjustment |
| Product / Procurement | 选款、采购、补货、供应商整改 | 销量速度、退货原因、批次利润、补货建议 |
| Tech / Data Team | 维护集成、数据模型和平台能力 | Integration Health、Replay、Schema Drift、Admin |
| Auditor / Security | 核查权限、Secret、操作和合规 | 只读 Audit、Credential、Data Access Log |

## 推荐角色模型

- `OWNER`：组织级最高审批，但不用于日常共享登录。
- `OPS_LEAD`：运营策略、任务分配和中风险审批。
- `MARKETPLACE_OPERATOR`：商品、广告和促销工作；按 Store / Platform 限域。
- `WAREHOUSE_OPERATOR`：订单履约、退货 QC、盘点和库存操作；按 Warehouse 限域。
- `FINANCE_ANALYST`：财务和成本读取、对账与关闭；不得修改平台库存或广告。
- `DATA_ANALYST`：访问去标识化经营数据和 Mart。
- `PLATFORM_ADMIN`：系统配置、集成、Job 和 Feature Flag；不得默认拥有财务审批权。
- `AUDITOR`：不可变只读访问审计记录。

## 责任原则

业务团队决定商业策略、底价、目标利润和商品定位；IT 团队提供数据、规则、流程和受控执行能力。系统不能把商业责任隐藏在“算法建议”后面，也不能要求开发人员替代运营批准高风险动作。

# 产品原则与全局硬规则

## 产品原则

1. **Official First-party Data First**：交易、库存、广告和结算优先采用平台官方 API/报表与内部 Ledger。
2. **Company Truth over Platform View**：公司利润必须合并采购、仓库和跨平台事实，不以某个平台后台单一数字为终点。
3. **Explain before Automate**：系统先解释问题和证据，再建议，再批准，最后才自动执行。
4. **Variant-level Economics**：款式级汇总不得掩盖颜色、尺码和批次差异。
5. **Immutable Evidence**：Raw Payload、库存流水、财务流水和审计事件不可静默覆盖。
6. **Late Data is Normal**：退货、结算、罚款和修正可能晚到；指标必须可重算并保留版本。
7. **Human-in-the-loop by Default**：高风险写操作必须人工审批，AI 不能直接拥有平台写权限。
8. **Platform Volatility is a Design Input**：接口变化是常态，Integration Layer 必须隔离变化。
9. **Operational Adoption over Dashboard Volume**：功能是否成功以任务闭环和决策质量衡量，而不是图表数量。
10. **Simple Architecture, Strong Controls**：不超前复杂化，但幂等、审计、恢复、安全和测试不可省略。

## 全局硬规则

### HR-01：Raw 不可丢失

每次成功或业务上有意义的失败调用，都要保存 Request Metadata、Response/Report、Hash、Schema Version、Source Time 与 Ingestion Time。核心表不得成为唯一事实副本。

### HR-02：所有同步幂等

同一 Platform、Account、Endpoint、Business Key 和 Source Version 重放不得产生重复订单、库存或财务流水。

### HR-03：库存以流水为审计真相

`inventory_stock` 是可重建 Snapshot；`inventory_transaction` 是不可变事实。采购入库、预留、释放、出库、退货、质检、报损、盘点和转移均必须产生 Transaction。

### HR-04：财务支持迟到事件和重算

已关闭期间收到新费用或退货时，创建 Adjustment 和新 Calculation Version，不允许无痕修改历史利润。

### HR-05：写平台必须完整受控

```text
Recommendation
  → Deterministic Guardrail
  → Dry Run / Impact Preview
  → Approval / Policy Authorization
  → Idempotent Outbox Command
  → Platform API Call
  → Readback Verification
  → Audit + Metric Follow-up
```

任何一步失败均不得伪装为成功；不确定状态进入人工处理队列。

### HR-06：Secret 与权限最小化

平台根账号不得共享。Read 与 Write Credential 分离；Secret 不得进入 Git、前端、日志、普通配置文件或聊天工具。

### HR-07：第三方竞品数据不得充当财务真值

第三方估算只能用于趋势、相对强弱和候选研究。不得直接触发采购、自动调价、利润核算或高额广告决策。

### HR-08：指标必须版本化

每个核心指标具有 Definition ID、Version、Effective Date、Owner、SQL/Logic Reference 和测试样例。修改公式需要影响分析与历史重算决策。

### HR-09：所有页面显示 Freshness

用户必须看见 `source_updated_at`、`ingested_at`、最后成功同步、数据 Confidence 和已知缺口。

### HR-10：禁止非授权自动化

不得通过平台页面爬虫、Selenium、模拟人工点击或未公开接口替代官方 API。Adapter 必须维护平台允许的访问方式和限制。

# 领域模型、术语与数据可信度

## 商品身份层级

```text
Product Master / Internal SPU
  └── Product Variant / Internal SKU
        ├── Color
        ├── Size
        ├── Barcode
        ├── Supplier / Purchase Batch
        ├── Ozon Offer ID / SKU / Listing Variant
        └── Wildberries nmID / chrtID / Barcode / Listing Variant
```

- **SPU**：业务上的款式或产品族，不直接用于库存和精确利润。
- **Variant / Internal SKU**：可独立库存、采购、销售和退货的最细内部商品单元。
- **Platform Listing**：某一 Store 在某 Marketplace 上的商品卡。
- **Listing Variant**：平台商品卡下的可销售规格；必须映射到 Internal SKU。
- **Purchase Batch**：用于成本、质量和供应商追责的采购批次。

## 订单与履约术语

- **Platform Order / Posting / Supply Object**：平台原生交易对象，不强制与内部订单一一同构。
- **Generic Sales Order**：跨平台统一业务对象。
- **Fulfillment Order**：仓库实际执行的拣货、包装和交运任务。
- **Reservation**：订单对可售库存的占用。
- **Completed Sale**：按内部状态模型确认的有效销售，不等同于“已下单”。
- **Retained Sale**：在定义窗口内未发生退货或拒收的销售。

## 财务术语

- **Operational Contribution Profit**：用于 T+1 决策的经营估算，允许使用可解释的预估退货损失和未最终结算费用。
- **Settled Contribution Profit**：基于官方结算和内部成本的高可信利润。
- **Adjustment**：关闭后迟到费用、退款或更正形成的独立调整，不覆盖原记录。
- **COGS**：按内部有效成本策略分摊至订单行的商品成本。

## 数据可信度等级

| 等级 | 典型来源 | 允许用途 |
| --- | --- | --- |
| A | 官方 Settlement / Realization、已关闭财务文件、内部已审核采购成本 | 正式财务复核、Settled Profit |
| B | 官方订单、库存、广告 API 和内部仓库流水 | 日常运营与 T+1 决策 |
| C | 平台 Analytics、搜索和统计报表 | 漏斗、趋势和运营诊断 |
| D | 内部模型估算、预测或缺失费用分摊 | 建议、模拟；必须显示假设 |
| E | 第三方竞品估算或非验证市场情报 | 候选研究；不得直接执行高风险动作 |

任何聚合指标的 Confidence 不得高于其关键输入中的最低可信等级。页面必须能够解释降级原因，例如“12 个订单尚未进入结算，退货损失采用过去 30 日 Variant 均值”。

## Source of Truth Matrix

| 业务事实 | 最高可信来源 | 冲突处理 |
| --- | --- | --- |
| 平台订单状态 | 平台官方订单/Postings API 或正式报表 | 保留平台原始状态，并映射到内部状态；不覆盖 Raw |
| 平台可见库存 | 平台官方库存 Snapshot | 与内部可售库存分别展示并生成 Drift |
| 物理库存 | 本地仓库 Inventory Ledger + 盘点 | 差异进入库存调查与 Adjustment |
| 商品身份 | Internal Product Master | 平台 ID 必须映射，不由平台标题猜测 |
| 采购成本 | Purchase Batch / 审批后的 Cost Version | 缺失时不输出伪精确利润 |
| 广告消耗 | 平台官方广告 API/报表 | 跨日修正允许滚动重算 |
| 结算收入与平台费用 | 官方 Settlement / Finance Report | API 估算与结算差异单独记录 |
| 竞品销量 | 无内部真实来源；仅第三方估算 | 只作趋势信号，标记 E 级 |

# 系统上下文与目标架构

## System Context

```text
Ozon Seller API / Performance API / Reports / Push
Wildberries Products / Prices / Stocks / Orders / Reports / Analytics / Promotion API
Local Warehouse / Procurement / Accounting / Manual Files
Third-party Market Intelligence (optional, low confidence)
                            │
                            ▼
Marketplace Integration Gateway
Auth • Capability Registry • Rate Limit • Retry • Cursor • Raw Archive • Replay
                            │
                            ▼
PostgreSQL Data Foundation
raw.*  core.*  ledger.*  mart.*  ops.*
                            │
                            ▼
Business Modules
Product • Listing • Order • Inventory • Return • Finance • Ads • Experiment
                            │
                            ▼
Operations & Decision Console
Command Center • Workbench • Tasks • Approval • Audit • AI Briefing
```

## 推荐技术栈

- Backend：Java 21、Spring Boot、Spring Security、Bean Validation；
- Database：PostgreSQL、Flyway；
- Frontend：React、TypeScript；
- Async：PostgreSQL Task / Outbox Table + Worker；
- Object Storage：S3-compatible，用于原始报表、Payload 和导出文件；
- Observability：OpenTelemetry、Metrics、Structured Logs、Alerting；
- Deployment：Docker；根据俄罗斯数据存储与运营环境选择受控主机或云；
- CI/CD：静态检查、Unit/Integration/Contract Test、Migration Check、Artifact 签名与环境 Gate。

## 为什么采用 Modular Monolith

首版业务边界仍会快速学习。Modular Monolith 可以在一个部署单元内保持模块隔离和事务一致性，同时避免早期微服务带来的网络失败、分布式事务、部署和观测复杂度。模块之间不得直接访问彼此的 Repository；通过 Application Service、Domain Event 或明确 Query Port 交互，为未来拆分保留边界。

## 推荐模块

```text
identity-access
organization-account
marketplace-integration
product-listing
order-fulfillment
inventory
return-quality
finance-profit
advertising-promotion
analytics-decision
experiment
operations-workflow
ai-copilot
admin-observability
```

## 数据 Schema

- `raw`：不可变平台响应、报表、Push 和文件；
- `staging`：短期解析、Schema Validation 和去重；
- `core`：跨平台统一业务实体；
- `ledger`：库存、财务、成本和广告事实流水；
- `mart`：面向查询、指标和决策的可重建数据；
- `ops`：任务、建议、审批、执行、告警和审计；
- `iam`：用户、角色、Scope、Service Account；
- `platform`：Endpoint、Capability、Credential Metadata、Sync Cursor 和变更记录。

# 端到端业务流程

## 账户接入与 Capability Discovery

1. Owner 确认店铺、法律主体、平台账户和仓库归属；
2. Security/Admin 创建最小权限 Credential，Secret 写入 Secret Manager；
3. 系统执行 Connectivity Test，不记录明文 Secret；
4. 根据 Credential Scope 探测或配置可用 Capability；
5. 建立 Endpoint Registry、Rate Limit、Subscription Requirement 和 API Version；
6. 首次 Backfill 只写 Raw，完成 Schema Validation 后再进入 Core；
7. 生成账户接入报告与数据缺口清单。

## 历史回填

```text
Define Time Window / Entity Scope
 → Create Backfill Manifest
 → Acquire Raw Data / Reports
 → Hash + Persist Immutable Evidence
 → Parse to Staging
 → Validate Schema and Referential Integrity
 → Normalize to Core / Ledger
 → Reconcile with Platform Totals
 → Publish Data Quality Issues
 → Freeze Backfill Report
```

回填必须可中断续传。每个 Batch 记录边界、Cursor、成功数、拒绝数、重复数、未知字段和重试结果。

## Daily Incremental Sync

```text
Schedule / Push Trigger
 → Acquire Job Lease
 → Respect Account + Endpoint Rate Limit
 → Call Platform API
 → Persist Raw Payload Before Normalize
 → Validate + Normalize
 → Recalculate Affected Partitions
 → Reconcile Expected Completeness
 → Publish Freshness, Drift and Exceptions
```

Push 仅降低延迟，不能替代 Reconciliation Polling。任何事件都可能重复、乱序或晚到。

## SKU Mapping

1. 导入平台 Listing、Variant、Barcode 和原生 ID；
2. 基于 Barcode、Supplier Code、属性和人工规则生成 Mapping Candidate；
3. 高置信候选可批量建议，但首次生效必须由商品运营确认；
4. 一对多、多对一、重复 Barcode 或无法识别记录进入 Exception Queue；
5. Mapping 具有 Effective Time 和历史版本；
6. Mapping 变更触发受影响 Mart 与利润重算。

## Order & Fulfillment

1. 接收平台订单/Postings；
2. 保存平台原生对象并映射为 Generic Order；
3. 建立 Inventory Reservation；
4. Warehouse Workbench 展示拣货、打包、交运与 SLA；
5. 状态变化产生领域事件并释放或扣减库存；
6. 定期将内部状态与平台状态 Reconcile；
7. 超时、取消、重复、缺货和未知状态进入 Exception。

## Return & QC

1. 接收 Return Request / Return Event；
2. Return Item 关联订单行、Internal SKU 和 Purchase Batch；
3. 仓库执行 QC：可重新销售、需要整备、供应商问题、报损或争议；
4. 产生对应 Inventory Transaction；
5. 关联 Refund、物流费用、损失和赔付；
6. 标准化退货原因反馈给 Listing、Size Guide、Supplier、采购和广告分析。

## Finance Close

```text
T+1 Preliminary Operational Profit
 → Rolling Reconciliation with Late Fees / Returns
 → T+7 Revised View
 → Monthly Soft Close
 → Settlement Reconciliation
 → Hard Close
 → Post-close Adjustment when required
```

关闭后新到事件不得无痕覆盖。系统必须显示利润版本和关闭状态。

## Recommendation → Approval → Execution

```text
Metric / Rule / AI Evidence
 → Recommendation
 → Deterministic Guardrail
 → Task or Approval
 → Dry Run & Impact Preview
 → Human Approval / Policy Authorization
 → Outbox Command with Idempotency Key
 → Platform Call
 → Readback Verification
 → Audit and Result Follow-up
```

## API Change Management

1. 每 Sprint 检查官方 Release Notes 和 Developer News；
2. Endpoint Registry 登记 Deprecated Date、Replacement、Schema Version；
3. Contract Test 在 Staging 或可控账户验证；
4. 双写/双读过渡期比较新旧结果；
5. 新方法稳定后切换；
6. 旧方法移除前完成 Replay 和 Regression；
7. 变更写入 ADR / Integration Decision Log。

# Functional Requirements

Requirement ID 是后续 Epic、Story、Test Case、Release Note 和缺陷追踪的固定引用。阶段/优先级采用：`0/M`、`1/M`、`2/M`、`3/M`；`S` 表示 Should，`C` 表示 Could。

## Identity & Access Management

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| IAM-001 | 0/M | 支持 Organization、Legal Entity、Marketplace Account、Store、Warehouse 的层级与授权范围。 | 用户只能访问被授予的主体、店铺和仓库。 |
| IAM-002 | 0/M | 支持独立用户、MFA、禁用、Session 管理和强制定期重新认证。 | 禁用用户的全部 Session 与 Token 立即失效。 |
| IAM-003 | 0/M | 实现 Owner、Ops Lead、Marketplace Operator、Warehouse、Finance、Analyst、Platform Admin、Auditor 等角色。 | 角色不可隐式继承不相关高风险权限。 |
| IAM-004 | 0/M | Read、Write、Finance、Ads、Credential Admin 权限分离。 | 不存在一个普通角色默认拥有全部权限。 |
| IAM-005 | 2/M | 高风险动作支持 Maker-Checker 和多级审批。 | 发起人不能完成受控动作的最终审批。 |
| IAM-006 | 0/M | 支持 Service Account，并限制 Scope、来源网络、用途和到期时间。 | 后台 Job 不使用个人账户。 |
| IAM-007 | 0/M | 记录登录、权限变更、Credential 元数据变更和敏感操作审计。 | 可按 Actor、Time、Entity、Action 检索。 |
| IAM-008 | 1/S | 支持 Store、Warehouse、Platform、Data Domain 限制的数据行级权限。 | 角色可被限制到指定店铺或仓库。 |
| IAM-009 | 1/M | 敏感导出和财务明细支持独立权限与水印/审计。 | 未授权用户不能通过导出绕过页面权限。 |
| IAM-010 | 2/S | 支持临时授权、到期回收和 Break-glass 流程。 | 紧急权限具有理由、时限和事后复核。 |

## Marketplace Integration Gateway

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| INT-001 | 0/M | 定义统一 Marketplace Adapter Contract；平台特有语义通过扩展对象保留。 | 业务模块不直接调用平台 HTTP Client。 |
| INT-002 | 0/M | 维护 Endpoint / Capability Registry。 | 记录版本、权限、限流、分页、Freshness、付费条件和替代方法。 |
| INT-003 | 0/M | Credential 从 Secret Manager 获取，应用库只保存引用和元数据。 | 日志、异常和 UI 不出现明文 Secret。 |
| INT-004 | 0/M | 支持 Schedule、Manual Trigger、Push/Webhook Trigger。 | 触发来源和运行参数可审计。 |
| INT-005 | 1/M | Push 与 Polling Reconciliation 并存。 | 漏掉 Push 不会造成永久数据缺失。 |
| INT-006 | 0/M | 支持 Cursor、Offset、Page、Date Window 等分页模式。 | 中断后从已确认 Cursor 继续。 |
| INT-007 | 0/M | 实现 Account + Endpoint 粒度 Rate Limiter。 | 429 不造成无界重试或账号风险。 |
| INT-008 | 0/M | 实现 Timeout、Exponential Backoff、Jitter、Retry Budget 和 Circuit Breaker。 | 错误分类与重试策略可配置。 |
| INT-009 | 0/M | 所有 Ingestion 幂等。 | 重放同一来源事件不重复生成 Core/Ledger。 |
| INT-010 | 0/M | 不可变保存 Raw API Response、Report File、Push Event 和 Manual Import。 | 每个 Normalize 结果可追溯 Raw ID/Hash。 |
| INT-011 | 0/M | 执行 Schema Validation，并捕获未知字段。 | 字段变化产生告警而不是静默丢弃。 |
| INT-012 | 0/M | 失败记录进入 Error/Exception Queue，并支持选择性 Replay。 | Replay 不需重新下载已保存 Raw。 |
| INT-013 | 0/M | 支持 90～180 天 Historical Backfill。 | 生成 Backfill Manifest、分批结果和差异报告。 |
| INT-014 | 0/M | 所有事实保存 Source Time、Ingestion Time、Processing Time。 | UI 可显示数据新鲜度。 |
| INT-015 | 1/M | 监控 Credential 到期、Scope 变化、Owner 变化和权限失效。 | 提前告警并提供 Rotation Runbook。 |
| INT-016 | 1/M | 监控官方 API Release Notes 与 Deprecated Capability。 | 废弃前创建迁移任务和 Contract Test。 |
| INT-017 | 2/M | 写操作经 Outbox / Command Store 执行。 | 命令具有 Idempotency Key、Approval 和状态机。 |
| INT-018 | 2/M | 所有写操作完成 Readback Verification。 | 不一致进入补偿或人工处理。 |
| INT-019 | 0/S | 支持 CSV、Excel、平台报表文件作为受控补充数据源。 | 文件有 Schema、Hash、Importer 和 Validation。 |
| INT-020 | 1/S | 提供 Integration Health Dashboard。 | 可见成功率、延迟、积压、429、5xx、Schema Error 和 Freshness。 |
| INT-021 | 1/M | 平台原生状态和错误码保留，内部映射版本化。 | 新增状态不会被错误归入成功。 |
| INT-022 | 2/S | 支持 Adapter Sandbox / Fixture 模式。 | 测试不依赖真实生产写操作。 |

## Product Master & Listing

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| PIM-001 | 0/M | 建立 Internal SPU、SKU/Variant、Color、Size、Barcode。 | 每个活跃平台 Variant 有唯一或明确异常的内部映射。 |
| PIM-002 | 0/M | 保存 Ozon/WB 原生商品标识和平台关系。 | Offer ID、SKU、nmID、chrtID、Barcode 等原样保留。 |
| PIM-003 | 0/M | 支持 Platform Listing 与 Listing Variant。 | 同一 Internal SKU 可映射多个平台 Listing。 |
| PIM-004 | 1/M | 建立内部类目与平台类目 Mapping。 | 变更有版本和生效日期。 |
| PIM-005 | 1/M | 建立 Attribute Mapping 和必填字段校验。 | 缺失或非法字段形成 Listing Issue。 |
| PIM-006 | 1/M | 支持俄罗斯语 Title、Description、Attribute、Size Guide 的内容版本。 | 可比较、审批和回滚变更。 |
| PIM-007 | 1/S | 保存 Media 元数据、排序、Hash 和使用版本。 | 可识别主图变化及实验版本。 |
| PIM-008 | 0/M | 支持 Size/Color 规范化与平台值映射。 | 未知值进入 Mapping Queue。 |
| PIM-009 | 0/M | COGS 支持 Supplier、Purchase Batch、Valid From/To。 | 历史利润使用当时有效成本版本。 |
| PIM-010 | 1/M | 计算 Listing Health。 | 可解释类目、属性、内容、尺码、价格、库存、评分等子项。 |
| PIM-011 | 1/M | 检测平台与内部主数据 Drift。 | 价格、属性、状态和变体差异可见。 |
| PIM-012 | 2/M | 商品卡写入前支持 Preview、Validation、Approval 和 Readback。 | 失败不破坏内部批准版本。 |
| PIM-013 | 1/S | 支持批量编辑建议，但每批有影响范围和 Dry Run。 | 单一错误不能无边界扩散。 |
| PIM-014 | 1/M | 商品变更与实验、任务和指标关联。 | 能解释某一时点前后发生了什么。 |

## Order & Fulfillment

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| ORD-001 | 0/M | 保存平台原生订单/Postings 与状态历史。 | 原生对象和状态变化可完整追溯。 |
| ORD-002 | 0/M | 映射为 Generic Sales Order / Line，但保留平台特有扩展。 | 内部统一查询不丢失平台语义。 |
| ORD-003 | 0/M | 订单行关联 Internal SKU、Listing Variant 和 Purchase Batch。 | 无法映射时阻止伪利润并进入异常。 |
| ORD-004 | 0/M | 支持 FBO/FBS 等履约模式并版本化映射。 | 不同模式费用和 SLA 可区分。 |
| ORD-005 | 1/M | 新订单创建库存 Reservation。 | 取消、超时和状态变化正确释放。 |
| ORD-006 | 1/M | Warehouse Workbench 展示拣货、打包、标签、交运和 SLA。 | 操作员看到明确下一动作和截止时间。 |
| ORD-007 | 1/M | 处理取消、部分取消、缺货、重复和平台回滚状态。 | 库存和财务影响正确。 |
| ORD-008 | 1/S | 支持 Shipment、Package、Label/Document 元数据。 | 文档生成和打印状态可审计。 |
| ORD-009 | 1/M | 定期执行平台与内部订单状态 Reconciliation。 | 差异生成 Issue，不静默覆盖。 |
| ORD-010 | 1/M | 订单超时与履约异常告警。 | 按风险和剩余 SLA 排序。 |
| ORD-011 | 2/M | 平台订单动作进入受控 Command。 | 具备审批、幂等和 Readback。 |
| ORD-012 | 1/S | 支持订单事件时间线。 | 用户能查看来源、状态、操作者和关联库存/财务事件。 |
| ORD-013 | 2/S | 支持仓库扫描与批量操作。 | 每次扫描绑定 Operator、Device 和时间。 |

## Inventory

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| INV-001 | 0/M | 建立 Warehouse、Location、Stock Type 和 Platform Allocation。 | 库存可按主体、仓库、SKU 和状态查询。 |
| INV-002 | 0/M | `inventory_transaction` 为不可变审计流水。 | 任何数量变化都有原因、来源和前后关系。 |
| INV-003 | 0/M | `inventory_stock` 为可重建 Snapshot。 | 可通过 Ledger 重放校验。 |
| INV-004 | 1/M | 支持 Receipt、Reservation、Release、Pick、Ship、Return、QC、Write-off、Count、Transfer。 | 每类交易有明确借贷方向与业务引用。 |
| INV-005 | 1/M | 计算物理、预留、质检锁定、安全库存和可售库存。 | 公式版本化且可解释。 |
| INV-006 | 1/M | 分别保存内部可售库存与平台可见库存。 | 差异形成 Inventory Drift。 |
| INV-007 | 1/M | 支持库存盘点与 Adjustment 审批。 | 盘点差异不直接覆盖历史。 |
| INV-008 | 1/M | 支持退货 QC 后重新入库、整备或报损。 | 状态与损失进入库存和财务流水。 |
| INV-009 | 1/M | 计算 Sales Velocity、Days of Cover、Stockout Date。 | 展示数据窗口和异常处理。 |
| INV-010 | 1/M | 识别 Dead Stock、Ageing、Slow Moving。 | 阈值可按类目配置。 |
| INV-011 | 2/M | 生成补货建议和平台分配建议。 | 考虑需求、交期、安全库存、利润和活动。 |
| INV-012 | 2/M | 库存写平台必须经过 Guardrail、Outbox 和 Readback。 | 不允许发布负数或超出可售量。 |
| INV-013 | 2/S | 支持跨仓/跨平台库存转移计划。 | 计划与实际执行、在途和成本关联。 |
| INV-014 | 1/S | 支持批次和保质/质量属性。 | 可追踪供应商质量问题。 |
| INV-015 | 1/M | 缺货风险与广告联动。 | 库存不足时生成暂停或限流建议。 |
| INV-016 | 2/S | 提供库存模拟。 | 促销、广告或补货假设不直接改变真实库存。 |

## Returns & Quality

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| RET-001 | 0/M | 接入平台退货、拒收、取消和退款事实。 | 保留原生原因和状态。 |
| RET-002 | 1/M | Return Item 关联订单行、SKU、平台 Listing 和 Purchase Batch。 | 能够定位尺码、颜色和批次问题。 |
| RET-003 | 1/M | Warehouse QC 支持 Resellable、Refurbish、Supplier Defect、Damaged、Dispute。 | 每个结果产生库存与财务后果。 |
| RET-004 | 1/M | 建立标准化 Return Reason Taxonomy。 | 保留原始文本和标准原因。 |
| RET-005 | 1/M | 支持俄罗斯语评价/原因文本分类与人工修正。 | 模型置信度低时进入人工队列。 |
| RET-006 | 1/M | 计算 Order、Variant、Size、Color、Batch、Region 退货率。 | 分母和观察窗口明确。 |
| RET-007 | 1/M | 关联退款、物流、整备、报损和赔付成本。 | 退货损失进入利润。 |
| RET-008 | 1/S | 检测尺码偏大/偏小、色差、面料、做工和包装主题。 | 主题趋势可下钻原始证据。 |
| RET-009 | 1/M | 高退货问题生成 Listing、Supplier 或 Warehouse Task。 | Task 具备证据与整改结果。 |
| RET-010 | 2/S | 支持平台争议和赔付跟踪。 | 状态、材料和金额可审计。 |
| RET-011 | 2/S | 退货整改与后续转化/退货指标关联。 | 评估整改是否有效。 |

## Finance, Settlement & Profit

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| FIN-001 | 0/M | 保存官方 Financial Transaction、Settlement、Realization 和 Report 原始文件。 | 每个规范化费用可追溯 Raw。 |
| FIN-002 | 0/M | 建立不可变 Financial Ledger。 | 取消、退款和修正使用反向或 Adjustment Entry。 |
| FIN-003 | 0/M | 统一 Commission、Logistics、Storage、Ads、Promotion、Penalty、Compensation、Refund、Tax Estimate。 | 费用类型映射有版本。 |
| FIN-004 | 0/M | COGS 分配到订单行。 | 缺失或冲突成本显式阻断最终利润。 |
| FIN-005 | 0/M | 计算 Operational Contribution Profit。 | 显示估算输入、Confidence 和版本。 |
| FIN-006 | 1/M | 计算 Settled Contribution Profit。 | 以关闭后的官方结算和内部成本为准。 |
| FIN-007 | 1/M | 支持 T+1、T+7、Soft Close、Hard Close。 | 每个期间有状态、负责人和差异。 |
| FIN-008 | 1/M | 支持 Late-arriving Event 与 Post-close Adjustment。 | 历史关闭值不被无痕覆盖。 |
| FIN-009 | 1/M | 平台总额、订单行、费用和银行/会计导出可 Reconcile。 | 差异按原因分类并可追踪。 |
| FIN-010 | 1/M | 按 Platform、Store、SPU、SKU、Color、Size、Batch、Campaign、Region 分析利润。 | 聚合结果与 Ledger 对平。 |
| FIN-011 | 1/M | 计算 Allowable CPA、Max CPC 和 Minimum Price。 | 公式使用当前有效成本和目标利润。 |
| FIN-012 | 1/M | 提供 Profit Calculation Version。 | 用户可查看公式、输入和版本。 |
| FIN-013 | 1/S | 支持税费和 Overhead 的可配置估算。 | 与法定会计明确区分。 |
| FIN-014 | 1/M | 识别负利润、高费用和异常费用。 | 生成可解释 Alert/Task。 |
| FIN-015 | 2/M | Promotion Simulator 输出利润和库存影响。 | 亏损或库存不足时阻止推荐参加。 |
| FIN-016 | 2/S | 支持多币种原始金额和汇率版本。 | RUB 报表可追溯换算。 |
| FIN-017 | 1/M | 财务导出有权限、时间范围和审计。 | 敏感字段不可通过未授权 API 获取。 |
| FIN-018 | 2/S | 支持财务锁定与变更审批。 | 关闭期间的修改有双人复核。 |

## Analytics & Decision Support

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| ANL-001 | 0/M | Daily Command Center 展示订单、GMV、净收入、利润、退货、库存和数据健康。 | 默认聚焦异常和行动，不只展示总量。 |
| ANL-002 | 0/M | 支持跨平台统一过滤和下钻。 | 用户可从公司总览下钻到 Variant 和原始证据。 |
| ANL-003 | 1/M | SKU Profitability 展示收入、COGS、费用、广告、退货和利润。 | 每项可解释来源和 Confidence。 |
| ANL-004 | 1/M | Funnel 展示曝光、点击、加购、订单、完成销售和退货。 | 不同平台缺失环节明确标记。 |
| ANL-005 | 1/M | 自动诊断 Low Impression、Low CTR、Low Conversion、High Return、Negative Margin。 | 规则版本化并可查看阈值。 |
| ANL-006 | 1/M | Inventory Intelligence 展示可售、速度、覆盖天数、断货和滞销。 | 异常生成任务。 |
| ANL-007 | 1/M | Return Intelligence 展示原因、尺码、颜色、批次和区域。 | 支持原始文本证据。 |
| ANL-008 | 1/M | Advertising Performance 展示 Spend、CTR、CPC、CPA、ACOS、TACOS、贡献利润。 | 广告销售与总销售口径分开。 |
| ANL-009 | 1/S | 搜索词和商品可见性分析。 | 支持趋势和变更前后比较。 |
| ANL-010 | 1/M | 所有图表显示 Source Time、Freshness 和 Confidence。 | 过期或缺失数据显式告警。 |
| ANL-011 | 1/S | 支持基准、环比、同比和同类 Hero SKU 对比。 | 比较组定义可见。 |
| ANL-012 | 1/M | 支持 Metric Drill-through。 | 从指标跳转订单行、费用或 Raw Evidence。 |
| ANL-013 | 1/S | 支持保存视图和定时报告。 | 权限与数据范围继承当前用户。 |
| ANL-014 | 2/S | 支持 Forecast 与 Scenario，但与 Actual 分离。 | 预测误差持续评估。 |
| ANL-015 | 1/M | Data Quality 与经营指标同屏可见。 | 不能在数据缺口时输出伪确定建议。 |

## Advertising & Promotion

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| ADS-001 | 1/M | 接入 Campaign、Target/Keyword、SKU、Budget、Bid 和日级指标。 | 保存平台原生 ID 和状态历史。 |
| ADS-002 | 1/M | 统一 Spend、Impression、Click、CTR、CPC、Order、CPA、Revenue。 | 平台差异通过 Definition 映射。 |
| ADS-003 | 1/M | 计算 Advertising Contribution Profit。 | 广告订单必须扣除商品、平台、物流和退货成本。 |
| ADS-004 | 1/M | 识别超出 Allowable CPA / Max CPC 的 Campaign 或 SKU。 | 产生暂停/调整建议而非直接写入。 |
| ADS-005 | 1/M | 低库存、高退货、负利润商品不建议扩大广告。 | Guardrail 可解释阻断原因。 |
| ADS-006 | 1/S | 搜索词按意图、品牌、品类、属性和无关词分类。 | 支持人工修正和规则维护。 |
| ADS-007 | 1/S | Campaign 与自然流量、Listing 变更和活动关联。 | 分析不把所有增长归因广告。 |
| ADS-008 | 2/M | 预算或 Bid 变更支持 Dry Run、Approval、Outbox 和 Readback。 | 高幅度变更触发更高审批级别。 |
| ADS-009 | 2/S | 支持负利润自动暂停的受控策略。 | 仅在数据 Fresh、规则稳定和授权范围内执行。 |
| ADS-010 | 1/M | Promotion Simulator 计算活动价、折扣、费用、利润和库存。 | 输出推荐、条件推荐或禁止。 |
| ADS-011 | 1/M | 维护 Minimum Price 与不可突破的商业底线。 | 平台活动不得覆盖内部底价而不告警。 |
| ADS-012 | 2/S | 支持促销前后 Baseline 和 Incrementality 评估。 | 区分销量增长与利润增量。 |
| ADS-013 | 1/S | 广告和活动变更关联 Experiment。 | 可追踪前后指标。 |
| ADS-014 | 2/S | 支持预算日内节奏和异常消耗告警。 | 突然消耗不会等待次日报表。 |

## Experiment Management

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| EXP-001 | 1/M | 登记主图、标题、价格、折扣、广告、详情、库存等实验。 | 每个实验有 Hypothesis、Owner、时间和目标指标。 |
| EXP-002 | 1/M | 保存 Control / Variant 和变更前版本。 | 实验结束可恢复原配置。 |
| EXP-003 | 1/M | 每次实验限制主要变量数量。 | 多变量变更必须显式标记为不可归因。 |
| EXP-004 | 1/M | 定义 Guardrail Metric 和 Stop Condition。 | 利润、库存或退货恶化时可提前停止。 |
| EXP-005 | 1/S | 支持平台原生 A/B 结果导入。 | 保留平台实验 ID 和方法说明。 |
| EXP-006 | 1/M | 实验结果自动关联前后指标与数据质量。 | 低样本或缺失数据不得标记 Winner。 |
| EXP-007 | 2/S | 支持实验模板和成功模式复用。 | 复制时重新检查商品和平台适用性。 |
| EXP-008 | 1/M | 实验结论进入 Knowledge Record。 | 失败实验同样保留，避免重复试错。 |

## Operations Workflow, Recommendation & Approval

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| OPS-001 | 1/M | 异常可转换为 Recommendation。 | 包含问题、证据、影响、建议、风险和 Confidence。 |
| OPS-002 | 1/M | Recommendation 可生成 Task。 | Task 有 Owner、Priority、Due Date、Evidence 和 Result。 |
| OPS-003 | 1/M | 支持 Task Queue、Assignment、Comment、Attachment 和状态流转。 | 逾期、阻塞和转派可见。 |
| OPS-004 | 1/M | 任务完成要求填写结果和可选验证期。 | 不能只点“完成”而无结果。 |
| OPS-005 | 2/M | 高风险操作生成 Approval。 | 显示 Before/After、影响范围、利润和库存影响。 |
| OPS-006 | 2/M | 支持 Maker-Checker 和审批级别。 | 发起人不能自批受控操作。 |
| OPS-007 | 2/M | 审批具有 Expiry；过期后必须重新计算。 | 旧价格、库存或数据不能继续执行。 |
| OPS-008 | 2/M | 支持 Dry Run / Impact Preview。 | 用户能看到预计修改对象和阻断项。 |
| OPS-009 | 2/M | 执行结果与 Approval、Command、平台响应和 Readback 关联。 | 端到端审计完整。 |
| OPS-010 | 2/M | 不确定执行状态进入 Manual Resolution。 | 系统不得重复提交可能已成功的命令。 |
| OPS-011 | 1/S | 支持 Daily Operations Brief 和 Weekly Business Review。 | 报告引用可点击指标和任务。 |
| OPS-012 | 1/M | Alert 去重、聚合、抑制和升级。 | 同一根因不产生告警风暴。 |
| OPS-013 | 1/S | 运营任务与指标改善进行 Follow-up。 | 系统能评估行动是否有效。 |
| OPS-014 | 2/S | 支持 Policy-authorized Low-risk Action。 | 政策定义范围、阈值、有效期和撤销开关。 |

## AI Operations Copilot

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| AI-001 | 1/S | 生成每日经营摘要并引用内部指标证据。 | 不得编造不存在的商品、费用或原因。 |
| AI-002 | 1/S | 解释异常指标，区分事实、推断和建议。 | 每个结论显示 Evidence Link 与 Confidence。 |
| AI-003 | 1/S | 聚类评价和退货原因。 | 保留原文并支持人工纠正。 |
| AI-004 | 1/S | 生成俄罗斯语标题、描述和属性建议。 | 发布前必须由授权母语人员审核。 |
| AI-005 | 1/S | 搜索词意图分类与无关词建议。 | 不直接修改广告。 |
| AI-006 | 1/S | 生成实验总结和 Weekly Business Review 草稿。 | 数字来自 Metric Service 而非模型自行计算。 |
| AI-007 | 1/M | Prompt/Response 不包含未授权个人数据或 Secret。 | 外部模型调用前进行字段白名单和脱敏。 |
| AI-008 | 1/M | AI 无平台 Credential 和直接写权限。 | 所有动作进入规则与审批。 |
| AI-009 | 2/S | 记录 Model、Prompt Version、Input References、Output、Reviewer。 | 结果可审计和复现。 |
| AI-010 | 2/S | 评估建议接受率、正确率和业务结果。 | 低质量能力可单独关闭。 |

## Administration & Observability

| Requirement ID | 阶段/优先级 | 需求 | 验收摘要 |
| --- | --- | --- | --- |
| ADM-001 | 0/M | 维护 Organization、Account、Store、Warehouse、Timezone、Currency。 | 配置变化可审计。 |
| ADM-002 | 0/M | 维护 Job Schedule、Backfill、Feature Flag 和 Capability。 | 生产写 Flag 默认关闭。 |
| ADM-003 | 0/M | 提供 Data Quality Dashboard。 | 展示完整性、唯一性、Referential Integrity、Freshness 和 Drift。 |
| ADM-004 | 0/M | 提供 Job Run、Error Queue、Replay 和 Dead-letter 管理。 | 人工恢复有权限与审计。 |
| ADM-005 | 1/M | 提供 Credential Metadata 和到期告警；不显示 Secret。 | Owner、Scope、到期、最后使用可见。 |
| ADM-006 | 1/M | 提供 API Schema/Endpoint 变更登记。 | 每个变更有 Owner 和迁移状态。 |
| ADM-007 | 1/M | 提供 Feature Flag Kill Switch。 | 可按平台、账户、能力立即关闭写操作。 |
| ADM-008 | 1/S | 提供 Metric Definition Registry。 | 公式、Owner、版本和测试可查。 |
| ADM-009 | 1/M | 提供 Audit Explorer。 | 可按用户、实体、命令、时间和敏感操作检索。 |
| ADM-010 | 1/M | 提供 System Health 与依赖状态。 | 可见 DB、Object Storage、平台 API 和 Worker 健康。 |
| ADM-011 | 2/S | 支持配置导入导出和环境差异比较。 | Secret 不随配置导出。 |
| ADM-012 | 1/M | 重要管理操作需要原因和二次确认。 | 危险操作不可误触。 |

# 页面与交互基准

## 全局 UX 原则

- **Action-first**：默认显示异常、风险、待办和影响，而不是先展示大量图表。
- **Progressive Disclosure**：管理层先看结论，运营可下钻到指标，技术/财务可继续下钻 Raw Evidence。
- **Freshness Visible**：每个页面都展示最后同步时间、数据窗口和已知缺口。
- **No False Precision**：估算利润、第三方数据和低样本实验不能使用与已结算数字相同的视觉语义。
- **Before/After Explicit**：任何变更审批清楚展示当前值、建议值、影响范围和阻断理由。
- **Safe Bulk Operations**：批量操作先 Preview、分组显示错误，并允许只执行通过项或全部取消。

## Daily Command Center

### 主要区域

1. 数据健康横幅：平台同步、最后成功时间、异常账户、Confidence；
2. 核心经营卡：订单、GMV、净收入、Operational Profit、Settled Profit、退货率；
3. 平台对比：Ozon / Wildberries 的利润、订单、库存和广告；
4. 今日 Priority Queue：负利润、缺货、高退货、超时履约、数据阻塞；
5. 待审批与待处理任务；
6. 过去 7/30 天趋势与重大变更标记。

### 页面动作

- 按 Platform、Store、Category、Product、Warehouse 过滤；
- 从卡片进入 SKU、订单、费用或任务；
- 创建 Task、指派 Owner、标记已知事件；
- 导出当前权限范围内的数据。

## SKU 360 / Profitability

顶部显示商品身份、平台 Listing、价格、库存、评分和最近变更。主体分为：

- Profit Waterfall；
- Funnel；
- Variant/Size/Color 对比；
- Inventory & Coverage；
- Returns & Review Themes；
- Ads / Promotion；
- Timeline：商品、价格、图片、库存、广告和任务变更；
- Recommendation 与 Experiment。

## Warehouse Workbench

按“需要立即处理”而非平台 API 对象组织：

- 即将超时订单；
- 待拣货、待包装、待交运；
- 缺货和商品身份异常；
- 待退货 QC；
- 待重新入库、整备、报损；
- 盘点差异和平台库存 Drift。

移动端或扫描场景应减少自由文本，使用大按钮、Barcode 扫描和明确确认反馈。

## Finance Close

页面必须同时显示：

- 当前期间状态；
- 平台 Settlement 总额与内部 Ledger；
- 未映射费用、未映射 SKU、迟到事件；
- Operational 与 Settled Profit 差异；
- Reconciliation Issue；
- Soft Close / Hard Close Checklist；
- Adjustment 和审批记录。

## Advertising Control

- Campaign / SKU / Keyword 多层级；
- Spend、CPC、CPA、ACOS、TACOS、Contribution Profit；
- Allowable CPA / Max CPC；
- 库存、退货和 Listing Health 风险；
- 建议：扩大、保持、缩减、暂停或先修复商品；
- 所有写建议默认进入 Dry Run。

## Recommendation Card

```text
问题：        过去 7 天曝光 18,200，CTR 0.61%
证据：        同类 Hero SKU 中位数 1.34%，价格与库存无明显异常
影响：        预计损失访问量与订单机会
建议：        先测试主图，不增加广告预算
风险：        主图改变可能影响现有转化
可信度：      C（平台 Analytics，小时级更新）
Owner：       Marketplace Operator
截止时间：    2026-08-12
后续验证：    7 天 CTR、加购率、订单转化、退货率
```

## Approval Detail

审批页必须显示：

- 发起原因与 Evidence；
- 受影响账户、Listing、SKU 和数量；
- 当前值 → 目标值；
- 预计收入、利润、库存和广告影响；
- Guardrail 结果；
- Dry Run 结果；
- Approval Chain 与有效期；
- 执行后 Readback 状态。

# 数据架构与核心模型

## Raw Layer

建议最少包含：

```text
raw_api_exchange
raw_report_file
raw_push_event
raw_manual_import
raw_payload_object
raw_payload_hash
raw_schema_observation
```

`raw_api_exchange` 保存 Platform、Account、Endpoint、Request ID、HTTP Status、Request Metadata、Source Window、Received Time、Payload Object Reference、Hash 和 Schema Version。大型 Payload 或报表存 Object Storage，数据库保存不可变引用和校验值。

## Core Layer

```text
organization
legal_entity
marketplace_account
store
warehouse

product_master
product_variant
product_barcode
platform_listing
platform_listing_variant
product_mapping
category_mapping
attribute_mapping
purchase_batch
cost_version

sales_order
sales_order_line
platform_order_reference
fulfillment_order
shipment
return_case
return_item
refund

ad_campaign
ad_target
promotion
review
search_query_metric
```

## Ledger Layer

```text
inventory_transaction
inventory_balance_snapshot
inventory_reservation
financial_transaction
settlement_document
settlement_line
cost_entry
advertising_spend_entry
profit_calculation_run
profit_calculation_line
period_close
adjustment_entry
```

### Ledger 设计要求

- 每条记录具有唯一业务 Key、Source Reference、Effective Time、Recorded Time；
- Correction 通过反向记录和新记录实现；
- 不允许普通应用代码物理删除；
- 聚合值必须可由 Ledger 重建；
- 同一 Source Event 重放得到相同结果；
- 关闭后的影响通过 Adjustment 进入新版本。

## Mart Layer

```text
mart_daily_store_performance
mart_daily_product_performance
mart_daily_sku_profit
mart_sku_funnel
mart_inventory_health
mart_return_reason
mart_ad_performance
mart_promotion_performance
mart_experiment_result
mart_data_quality
```

Mart 是派生数据，可按 Partition 重建。所有 Mart 记录应保存 `metric_definition_version`、`calculation_run_id` 和 Freshness。

## Ops Layer

```text
ops_recommendation
ops_task
ops_task_event
ops_approval
ops_approval_event
ops_command
ops_command_attempt
ops_readback
ops_alert
ops_audit_event
ops_feature_flag
```

## 关键关系

- `product_variant` 1:N `platform_listing_variant`；
- `sales_order_line` 必须关联 `platform_listing_variant`，成功 Mapping 后关联 `product_variant`；
- `inventory_transaction` 关联 `product_variant`、`warehouse` 和业务 Reference；
- `financial_transaction` 可先落地到平台对象，再经 Mapping/Allocation 关联订单行或 SKU；
- `profit_calculation_line` 引用收入、成本和费用 Ledger Entry 集合；
- `ops_command` 必须引用批准对象和业务实体版本，防止审批后对象已变化。

## 时间模型

至少区分：

- `source_event_time`：平台或业务事件发生时间；
- `source_updated_at`：来源最后更新时间；
- `ingested_at`：系统采集时间；
- `recorded_at`：进入 Ledger/Core 的时间；
- `effective_from/to`：业务版本生效区间；
- `calculated_at`：指标计算时间；
- `closed_at`：期间关闭时间。

所有内部时间存 UTC；显示层按 Store/Warehouse 时区转换。日界线和平台报表时区必须在 Metric Definition 中明确。

# 指标、利润口径与决策规则

## 销售漏斗

```text
Impressions
  × CTR
= Product Visits
  × Add-to-cart Rate
= Adds to Cart
  × Order Conversion Rate
= Orders
  × Completion / Buyout Rate
= Completed Sales
  × (1 - Return Rate)
= Retained Sales
```

不同平台可用字段可能不同。缺少某环节时，系统不得自行填 0；应显示 Not Available，并降低 Confidence。

## 核心利润公式

### Net Sales

```text
Net Sales = Gross Item Revenue
          - Seller-funded Discount
          - Refund / Cancellation Impact
          ± Platform Compensation / Adjustment
```

### Contribution Profit

```text
Contribution Profit
= Net Sales
- COGS
- Platform Commission
- Fulfillment / Delivery
- Storage
- Return and Refurbishment Loss
- Advertising Spend
- Promotion Cost
- Variable Tax Estimate
- Other Variable Fees
```

### Allowable CPA

```text
Allowable CPA
= Expected Selling Revenue
- COGS
- Expected Platform Fees
- Expected Fulfillment Cost
- Expected Return Loss
- Required Contribution Profit
```

### Max CPC

```text
Max CPC = Allowable CPA × Order Conversion Rate
```

以上公式必须使用同一时间、同一 Variant 和同一成本版本。若转化样本不足，Max CPC 只作为低可信建议。

## Minimum Price

```text
Minimum Price
= COGS
+ Expected Platform Fees at Price
+ Expected Logistics / Storage
+ Expected Return Loss
+ Required Profit
+ Safety Buffer
```

平台费率可能随价格和履约方式变化，不能用固定百分比覆盖所有情况。Promotion Simulator 应按平台规则和当前账户条件计算。

## 诊断规则示例

| 规则 | 建议条件 | 默认动作 |
| --- | --- | --- |
| LOW_IMPRESSION | 曝光显著低于自身历史或基准，且 Listing 可售 | 检查类目、属性、搜索词、价格、库存和物流 |
| LOW_CTR | 曝光足够，CTR 低于基准且数据 Fresh | 优先主图、标题、价格展示和信任要素实验 |
| LOW_CONVERSION | 访问足够，下单转化低 | 检查详情、尺码、价格、评价、配送日期 |
| HIGH_RETURN | 完成销售样本足够，退货率高于阈值 | 下钻 Size/Color/Batch/Reason，暂停放量 |
| NEGATIVE_MARGIN | Operational 或 Settled Contribution Profit < 0 | 停止扩大广告/活动并创建利润调查 |
| STOCKOUT_RISK | Days of Cover 低于补货提前期 + 安全天数 | 补货或限制广告、重新分配平台库存 |
| DEAD_STOCK | 库存龄和销量速度超过阈值 | 价格/活动实验、跨平台转移或退出方案 |
| DATA_BLOCKED | 关键数据缺失或 Mapping 错误 | 禁止高风险建议并创建数据修复任务 |

## 规则执行顺序

1. Data Quality Gate；
2. Listing / Sellability Gate；
3. Inventory Safety Gate；
4. Profit Guardrail；
5. Compliance / Permission Gate；
6. Business Strategy Rule；
7. AI Explanation 或 Ranking。

AI 只能在确定性 Gate 之后补充解释、排序或文本建议，不能覆盖 Gate。

## Hero SKU 分层

建议由系统支持但不自动决定：

- **Hero**：供应稳定、利润为正、转化可验证、退货可控，允许优先库存和实验；
- **Growth**：有潜力但样本不足，小预算、小库存验证；
- **Long-tail**：维持可售和低成本履约；
- **Repair**：存在内容、尺码、退货或利润问题，先整改；
- **Exit**：多轮实验失败、持续亏损或供应不稳定，停止补货并清理库存。

# Ozon / Wildberries 集成设计基准

## 原则

具体 Endpoint、字段和权限不得仅以本文件为准。每次开发前必须用官方文档校验，并把结果记录到 `Platform API Capability Matrix`。系统应依赖内部 Capability 名称，例如 `ORDER_READ`、`STOCK_WRITE`，由 Adapter 将其绑定到当前有效平台方法。

## Ozon Capability 范围

首轮建议覆盖：

- Product / Listing / Attribute / Content；
- Price 与可见促销信息；
- Stock、Warehouse、FBO/FBS Posting；
- Order、Cancellation、Shipment、Return；
- Finance Transaction、Realization、Settlement Report；
- Analytics、Product Query/Search Data（按账户可用能力）；
- Performance Campaign、Spend 与 Statistics；
- Push Notification 连接与事件（可用时）；
- Supply/FBO 能力只在业务确需时纳入阶段。

Ozon 官方公开了 Seller API 的角色与方法映射，并持续替换旧方法。2026 年新的 Seller API Key 采用 180 天有效期；财务方法也发生替换。因此 Credential Rotation、Endpoint Registry 和 deprecation migration 是首版必要能力，不是后期优化。

## Wildberries Capability 范围

首轮 Read Integration 建议覆盖：

- Products、Content、Prices & Discounts；
- Seller Warehouse 与 Stocks；
- FBS Orders / Supplies（按实际履约模式）；
- Sales、Returns、Main Reports、Financial Reports；
- Analytics；
- Promotion / Campaign Statistics；
- Tariff 或费用参考数据（以账户权限为准）。

WB API Token 具有有效期和权限类别，Analytics 与部分报表存在明确更新频率。系统必须按数据源自身 Freshness 展示，而不是把所有数据标为实时。WB API 更新频繁，因此每个 Sprint 必须执行官方变更检查和 Contract Test。

## 初始同步频率建议

| 数据域 | 目标频率 | 说明 |
| --- | --- | --- |
| 订单 / 取消 / 状态 | Push（可用时）+ 5 分钟补偿轮询 | 根据 Rate Limit、业务量和平台允许范围调整 |
| 平台库存 | 10～15 分钟 | 内部库存变化可更实时，但平台回读单独记录 |
| 商品 / Listing / 价格 | 30～60 分钟 | 变更后执行定向 Readback |
| 广告 Campaign / Spend | 30～60 分钟或官方更新周期 | 不可把延迟数据用于实时自动加预算 |
| Analytics / Funnel | 按官方更新频率，通常小时级或日级 | 页面显示 Source Freshness |
| 财务交易 | 每日增量 + 滚动回看 | 迟到事件触发受影响期间重算 |
| Settlement / Realization | 每日检查、按报告周期获取 | 用于高可信利润和关闭 |
| 历史修正 | 每日回看 30～90 天 | 范围按平台修正规律配置 |

## Capability Matrix 必填字段

```text
platform
account/store scope
capability code
endpoint/method
api version
read/write
credential type and scope
subscription requirement
rate limit and quota
pagination/cursor model
data freshness
business key
idempotency support
known late-data behavior
schema version
deprecated date
replacement capability
last verified date
owner
contract test status
```

## 平台状态映射

- 保留原始状态字符串和完整 Payload；
- 统一状态仅用于跨平台查询，例如 `NEW`、`READY_TO_FULFILL`、`IN_FULFILLMENT`、`COMPLETED`、`CANCELLED`、`RETURNED`、`UNKNOWN`；
- 映射配置版本化，新增原始状态默认进入 `UNKNOWN` 并告警；
- 不能把未知状态自动当作完成或取消。

## 限流与重试

- Rate Limit 以 Account + Endpoint + Credential 为基本粒度；
- 429 遵循平台返回信息并使用 Jitter；
- 4xx 业务错误不得盲目重试；
- 5xx/Timeout 使用 Retry Budget，防止重试风暴；
- 写操作发生 Timeout 时，先 Readback 或查询幂等 Key，不直接重复提交；
- 所有失败保存可关联 Request ID 和平台 Incident ID。

# 内部 API 与工程实现标准

## API 风格

- 前后端采用版本化 HTTP API；
- 外部平台对象不直接暴露为内部 API Contract；
- 写接口使用明确 Command 语义，而不是模糊 CRUD；
- 列表 API 支持 Cursor 或稳定分页、排序和过滤；
- 所有时间包含时区语义；金额使用 Decimal + Currency，不使用浮点；
- Error Response 包含稳定错误码、Correlation ID、用户安全消息和可诊断 Detail Reference。

## 示例：创建价格变更建议

```json
{
  "listingVariantId": "lv_123",
  "currentPrice": {"amount": "2499.00", "currency": "RUB"},
  "proposedPrice": {"amount": "2299.00", "currency": "RUB"},
  "reasonCode": "PROMOTION_TEST",
  "evidenceRefs": ["metric_456", "experiment_789"],
  "validUntil": "2026-08-15T18:00:00Z"
}
```

响应应返回 Recommendation / Approval 对象，不直接调用平台。

## 幂等

- Ingestion：`platform + account + source_object_id + source_version/event_id`；
- Command：Client/Server 生成 `idempotency_key`，并绑定业务实体版本；
- Report Import：`platform + account + report_type + period + file_hash`；
- Ledger：Source Reference + Transaction Type + Effective Time/Sequence；
- 重复请求返回原结果，不创建第二条业务动作。

## Outbox / Worker

```text
PENDING → LEASED → EXECUTING → SUCCEEDED
                         ↘ RETRY_WAIT
                         ↘ UNKNOWN_REQUIRES_READBACK
                         ↘ FAILED_FINAL
```

Worker 使用数据库 Lease、Heartbeat、Attempt Count、Next Run At 和 Dead-letter。生产写命令与业务审批在同一数据库事务中创建 Outbox Record。

## 事务与一致性

- Core + Ledger 的关键规范化在单一数据库事务内完成；
- Mart 使用异步可重建模型，允许最终一致；
- UI 对关键写操作显示“已接收”“平台已确认”“回读一致”等不同状态；
- 不使用模糊的单一 `SUCCESS` 覆盖多阶段事实。

## 代码边界

- Domain 不依赖平台 SDK；
- Adapter 将平台 DTO 转为 Canonical DTO / Domain Command；
- Repository 不跨模块直接调用；
- Metric Logic 集中在版本化 Calculation Service；
- Platform-specific 字段存扩展对象，但不污染通用领域核心；
- 所有外部日期、金额、枚举和数量进行严格 Validation。

## 数据迁移

Flyway Migration 必须：

- 向前兼容或提供明确停机窗口；
- 大表变更分阶段执行；
- 不在同一 Migration 中执行不可控远程调用；
- 具有生产数据量验证；
- 破坏性变更先完成 Backfill、双读和回滚计划。

# Non-functional Requirements

## Availability & Recovery

| NFR ID | 要求 | 初始目标 |
| --- | --- | --- |
| NFR-AVL-01 | 运营 Console 在业务时段可用。 | 月度可用性目标 99.5%，不含计划维护 |
| NFR-AVL-02 | 平台 API 故障不导致内部历史数据不可用。 | 降级展示最后可信数据与 Freshness |
| NFR-AVL-03 | 数据库支持 Point-in-time Recovery。 | RPO ≤ 15 分钟，RTO ≤ 4 小时，按环境验证 |
| NFR-AVL-04 | Object Storage Raw Evidence 有版本和校验。 | 不得因单节点故障丢失 |
| NFR-AVL-05 | 所有 Worker 可安全重启和重放。 | 无重复 Ledger / Command |

## Performance

- Command Center 常用查询 P95 ≤ 3 秒；
- SKU 360 常用时间窗 P95 ≤ 4 秒；
- 大范围导出采用异步 Job；
- API 列表默认有最大页大小；
- 大型 Mart 按日期、账户和实体合理分区/索引；
- 不为追求页面速度牺牲指标可追溯性。

## Scalability

首版以单一经营主体和多个 Store/Warehouse 为目标，但模型必须包含 Organization 和 Account Scope。容量规划至少覆盖：

- 10 倍当前 SKU；
- 20 倍日订单；
- 180 天 Raw 与多年 Ledger；
- 多账户并行同步；
- 运营高峰期间的订单和库存 Job。

## Data Quality

- Completeness、Uniqueness、Validity、Referential Integrity、Freshness、Reconciliation 均有自动检查；
- 关键检查失败会阻断下游高风险 Recommendation；
- Data Quality Issue 具有 Owner、Severity、Affected Period 和 Resolution；
- 修复后支持定向重算。

## Maintainability

- 模块级架构测试防止依赖越界；
- Endpoint/Metric/Rule 配置具有 Owner 和版本；
- 关键流程具备 Runbook；
- 外部 API DTO 与内部模型分离；
- 代码、Migration 和配置统一 Review。

## Accessibility & Internationalization

- UI 主语言可先采用中文/英语内部版本，但平台内容和业务字段必须完整支持俄语；
- 数据库、API 和导出使用 UTF-8；
- 日期、金额、千分位和时区按用户/Store 设置显示；
- 关键页面支持键盘操作、可读 Contrast 和明确错误反馈。

# 安全、账号、隐私与合规

## 平台账号治理

- 根账号始终由俄罗斯经营主体 Owner 控制；
- 开发、运营、仓库和财务使用独立用户或 Token；
- Read、Finance、Inventory Write、Price Write、Ads Write Credential 分离；
- 每个 Credential 登记 Owner、Store、Scope、Purpose、Created At、Expires At、Last Used At；
- 到期前 30/14/7 天告警，采用新旧 Credential 重叠验证后切换；
- 立即支持 Revoke 和对应 Capability Kill Switch。

## Secret 管理

禁止：

- 写入 Git 或 Docker Image；
- 下发到 Browser；
- 输出到日志、Tracing、Error Message；
- 通过群聊或普通工单传播；
- 在数据库明文保存平台 Token。

应用通过 Secret Manager 和 Workload Identity 获取；访问 Secret 本身也需审计。

## 应用安全

- MFA、RBAC、Scope 和最小权限；
- CSRF、XSS、SQL Injection、SSRF、File Upload 等常规防护；
- 导入文件进行类型、大小、病毒和 Schema 检查；
- 所有高风险 API 需要重新认证或 Step-up；
- Rate Limit 既保护外部平台，也保护内部敏感接口；
- 管理与生产写入口不对公共互联网开放或采用强网络控制。

## 个人数据最小化

只保存履约、退货和审计真正需要的买家数据。优先保存平台订单 ID 和去标识化分析字段；姓名、电话、地址等需要：

- 明确处理目的和保留周期；
- 字段级访问控制和加密；
- 禁止进入 Analytics Mart，除非确有必要；
- 禁止发送给外部 AI；
- 导出、访问和删除具备审计；
- 测试环境使用脱敏或合成数据。

俄罗斯个人数据本地化和跨境传输具有正式监管要求。工程基线采用保守策略：俄罗斯公民个人数据的主要数据库放在俄罗斯境内；任何跨境传输或外部 AI 处理在实施前由本地法律与安全负责人确认通知、合同和技术控制。

## Audit

审计事件至少包括：

- 登录、失败登录、MFA 和 Session；
- 权限、角色、Scope 和 Credential 元数据变更；
- Raw Replay、数据修复、Mapping 和成本变更；
- 价格、库存、广告、促销和订单写操作；
- Approval、Reject、Override、Kill Switch；
- 财务关闭、解锁和 Adjustment；
- 敏感数据查看和导出。

Audit Event 采用 Append-only、服务端时间、Correlation ID 和 Hash/完整性保护；普通管理员不得删除。

# AI Operations Copilot

## 产品定位

AI 是建立在可信数据、指标服务和规则引擎之上的解释与生产力层。它不负责自行计算核心财务事实，也不直接连接 Marketplace Credential。

## 首批场景

1. Daily Business Brief：总结变化、异常、风险和待办；
2. Return & Review Theme：归纳尺码、质量、色差、包装和场景问题；
3. Listing Content Assistant：生成俄罗斯语标题、描述、属性和尺码说明建议；
4. Search Intent Classifier：分类品牌、品类、属性、场景和无关查询；
5. Anomaly Explanation：用已计算指标解释问题并提出验证步骤；
6. Experiment Summary：生成前后指标、限制和建议结论；
7. Weekly Business Review Draft：将已批准指标和任务自动整理为会议材料。

## AI 安全链路

```text
Approved Data View
 → Field Allowlist / PII Redaction
 → Prompt Template + Metric References
 → Model Invocation
 → Structured Output Validation
 → Deterministic Business Rule Check
 → Human Review
 → Task / Recommendation only
```

## 输出要求

AI 输出必须区分：

- **Fact**：来自系统指标或原始文本；
- **Inference**：基于多个事实的推断；
- **Recommendation**：建议动作；
- **Unknown**：缺失数据或不能确定的部分。

每个 Fact 提供 Evidence Reference；每次调用记录 Model、Prompt Version、输入引用、输出、Reviewer 和后续业务结果。

## 禁止场景

- 模型直接持有平台 Token；
- 模型直接提交价格、库存、预算或促销；
- 将个人数据、Secret、完整订单地址发送给外部模型；
- 将 AI 猜测写成已结算利润；
- 未经俄罗斯语人员 Review 直接发布内容；
- 用 AI 代替确定性最低利润、权限和库存规则。

# 测试与质量保障

## 测试金字塔

- **Unit Test**：状态映射、金额、利润、库存、规则和 Parser；
- **Property / Invariant Test**：Ledger 平衡、幂等、重放、库存不变量；
- **Integration Test**：PostgreSQL、Flyway、Object Storage、Worker；
- **Contract Test**：Ozon / WB Request、Response、Schema 和错误样例；
- **Replay Test**：真实脱敏 Raw Payload 的重复处理；
- **Reconciliation Test**：平台总额与内部结果；
- **End-to-end Test**：从 Ingestion 到 Dashboard、Task、Approval 和 Readback；
- **Security Test**：权限越界、Secret 泄漏、导出、文件上传；
- **Performance Test**：历史回填、日增量和高峰查询；
- **Disaster Recovery Drill**：备份恢复、Worker 重放和 Kill Switch。

## 必测不变量

1. 相同 Raw Event 重放不产生重复订单或 Ledger；
2. Inventory Snapshot 可由 Transaction 重建；
3. Reservation 不使可售库存无解释地为负；
4. 财务 Adjustment 不覆盖原记录；
5. Profit 聚合与明细对平；
6. 未映射 SKU 不产生伪精确 COGS；
7. 未审批或过期审批不能创建平台命令；
8. 平台写 Timeout 不会盲目重复提交；
9. Readback 不一致不会被标记成功；
10. 禁用 Feature Flag 后无新写命令执行。

## Golden Dataset

建立一套版本化、脱敏的 Golden Dataset，至少包含：

- 正常订单完成；
- 部分取消；
- 重复 Push；
- 乱序状态；
- 退货跨月；
- 结算迟到费用；
- SKU Mapping 冲突；
- 价格或库存写入 Timeout；
- 新未知枚举；
- 广告数据修正；
- 多仓和多尺码库存；
- 负利润与高退货样例。

## Traceability

每个 Requirement ID 至少映射：

```text
Requirement → Design/ADR → Code Module → Test Case → Release → Evidence
```

Phase Gate 审查必须基于 Traceability Matrix 和真实数据验证，不以演示视频代替。

# DevOps、可观测性与运维

## 环境

- Local：合成数据和 Fixture；
- Integration：平台 Sandbox 或受控 Read Account；
- Staging：接近生产配置，写 Capability 仍默认关闭；
- Production：俄罗斯受控环境，严格 Secret、审批和审计。

环境之间不得复制未脱敏买家个人数据。配置使用环境变量/Secret Reference，非 Secret 配置版本化。

## CI Gate

每次 Merge 至少执行：

- Build、Lint、Unit Test；
- Architecture Boundary Test；
- Migration Validation；
- Integration Test；
- API/Schema Contract Test；
- Secret Scan、Dependency Scan、SAST；
- Frontend Type Check 和关键 E2E；
- Requirement/Test Traceability 检查（正式阶段）。

## CD Gate

- Migration Preview；
- Feature Flag 状态确认；
- Credential 与依赖健康检查；
- 数据兼容性和回滚策略；
- 部署后 Smoke；
- Job Backlog、Error Rate、Freshness 和 Data Quality 观察；
- 写能力需独立 Approval 才能开启。

## 关键 Metrics

### Integration

- API success/error by platform/account/endpoint；
- latency、429、5xx、timeout；
- last successful cursor；
- backlog age；
- schema validation error；
- Credential expiry；
- data freshness lag。

### Data

- unmapped SKU；
- duplicate/rejected event；
- order completeness；
- inventory drift；
- finance reconciliation difference；
- Mart calculation delay。

### Operations

- open/overdue task；
- approval age；
- command success/readback mismatch；
- negative-margin alert；
- stockout risk；
- return anomaly。

## Logging

Structured Log 至少包含 Correlation ID、Job Run ID、Platform、Account ID、Endpoint/Capability、Entity Reference、Attempt 和 Safe Error Code。禁止记录 Token、完整 PII、原始支付信息和大 Payload；Raw Payload 使用受控 Object Reference。

## Runbook

首版必须具备：

- Platform API outage；
- 429 / IP block 风险；
- Credential expiry / revoke；
- Schema change；
- Backlog accumulation；
- Raw replay；
- Inventory drift；
- Finance mismatch；
- Unknown write result；
- Feature Flag emergency shutdown；
- Database restore；
- Personal data incident escalation。

# 交付路线图与 Sprint 建议

## Phase 0：第 1～10 个工作日——Data, Identity & Visibility Foundation

### 业务输入

- 账户、店铺、仓库和履约模式；
- 活跃商品、Barcode、内部货号、颜色、尺码；
- 采购成本与批次；
- 90～180 天订单、库存、退货、广告和财务文件；
- 当前运营、仓库和财务人员权限。

### IT 交付

- 项目骨架、CI/CD、PostgreSQL、Flyway、Secret；
- Marketplace Account / Credential Registry；
- API Capability Matrix v1；
- Raw Layer 与 Object Storage；
- Ozon/WB Read Connector 骨架；
- SKU Mapping Registry；
- Historical Backfill；
- Data Quality Dashboard；
- Daily Business Report v1。

### Gate

- 活跃 Variant 映射率达到阶段标准；
- 历史数据可重复导入且幂等；
- 每日销量、库存、退货和基础成本可按平台/SKU 展示；
- Credential 无泄漏并有到期治理；
- 关键数据缺口有明确 Issue。

## Phase 1：第 3～6 周——Ozon Closure + WB Unified Read

### Ozon

- Product、Listing、Order、Stock、Return、Finance 自动同步；
- Performance Ads 接入；
- Push + Reconciliation（可用时）；
- SKU Profit、Funnel、Inventory、Return 和 Daily Ops Board。

### Wildberries

- Product、Price、Stock、Sales、Return、Finance、Analytics Read；
- SKU Mapping；
- 跨平台统一利润和经营对比；
- 不开放未经验证的价格/库存写入。

### Gate

- Closed Day 订单行高度一致；
- T+1 Operational Profit 可用且可解释；
- Data Quality 和 Freshness 可见；
- 运营人员开始使用任务而非仅看报表。

## Phase 2：第 7～12 周——Operations Workbench & Controlled Write

- Warehouse Workbench；
- Inventory Ledger 与盘点；
- Return QC；
- Listing Health；
- Ads / Promotion Simulator；
- Experiment Registry；
- Recommendation、Task、Approval；
- Shadow Mode；
- Ozon 少量库存、订单或价格 Capability 的受控写入；
- WB 写能力根据 Read Integration 稳定程度进入后续。

### Shadow Mode 判定

系统连续生成建议，但由运营人员手工在平台操作。比较建议与人工决定、执行结果和业务指标；达到准确性、覆盖率和恢复条件后，再开放 API Write。

## Phase 3：第 4～6 个月——Guardrailed Automation & AI

- Replenishment Recommendation；
- Platform Allocation；
- Minimum Price Guardrail；
- Budget Guardrail；
- Low-stock Ads Protection；
- Negative-margin Pause（政策授权范围内）；
- AI Review/Return Classification；
- Listing Content Copilot；
- Daily/Weekly AI Briefing。

## 推荐 Sprint 0

1. 领域 Workshop：商品、订单、库存、退货、财务；
2. Owner 决策与业务口径确认；
3. API Capability 调研和 Credential 治理；
4. Product ID / SKU Mapping 样本；
5. 平台历史数据抽样与 Data Profiling；
6. 架构 ADR：Modular Monolith、Raw、Ledger、Outbox；
7. Metric Definition v1；
8. Golden Dataset 与首批 Acceptance Test；
9. Phase 0 Backlog 和责任人。

## 推荐 Epic

| Epic | 目标 | 主要 Requirement |
| --- | --- | --- |
| E01 Account & Security Foundation | 账户、权限、Credential 与审计 | IAM-001～010, ADM-001/005/009 |
| E02 Integration Foundation | Adapter、Raw、Job、Replay、Capability | INT-001～022 |
| E03 Product Identity | Internal SKU 与平台映射 | PIM-001～009 |
| E04 Historical Data Truth | 订单、库存、退货、财务回填 | ORD/INV/RET/FIN Phase 0 |
| E05 Daily Command Center | 跨平台经营可见性 | ANL-001/002/010/015 |
| E06 Ozon Operational Closure | Ozon 商品到利润闭环 | PIM/ORD/INV/RET/FIN/ADS |
| E07 WB Unified Read | WB 数据接入和统一对比 | INT + ANL + FIN Read |
| E08 Warehouse & Return Workbench | 履约、库存和 QC | ORD-005～013, INV, RET |
| E09 Decision Workflow | 建议、任务、审批和执行 | OPS, INT-017/018 |
| E10 Experiment & Growth | 广告、促销和实验 | ADS, EXP |
| E11 AI Copilot | 解释、内容和摘要 | AI-001～010 |

# 团队组织与治理机制

## 推荐 Squad

### Platform & Data Squad

Tech Lead/Architect、Backend、Data Engineer、QA。负责 Adapter、Raw/Core/Ledger、同步、数据质量、财务和库存基础。

### Operations Product Squad

Product Manager/BA、Backend、Frontend、QA。负责 Command Center、Warehouse、Order、Return、Task、Approval 和 Audit。

### Growth Intelligence Squad

Data Analyst、Backend/Data、Frontend、俄罗斯语运营专家。负责 Funnel、Listing Health、Ads、Promotion、Experiment、Recommendation 和 AI Briefing。

共享 DevOps/SRE、Security、Finance Key User、Warehouse Key User、Ozon/WB Operations Key User。

## 决策机制

- **Owner Decision**：产品边界、底价原则、高风险自动化、法律主体与账户控制；
- **Product Decision**：流程、优先级、用户体验和运营采用；
- **Architecture Decision Record**：模块边界、数据、集成、安全和部署；
- **Metric Definition Review**：Finance + Ops + Data 共同批准；
- **Platform Capability Review**：每 Sprint 更新；
- **Go-live Review**：Product、Engineering、QA、Security、Operations 共同签署。

## Definition of Ready

Story 开发前必须具备：

- Requirement ID；
- 用户与业务问题；
- 数据来源和 Source of Truth；
- 关键状态/异常；
- Acceptance Criteria；
- 权限和审计要求；
- 数据迁移/回填影响；
- 依赖的 Platform Capability；
- 观测和恢复方式。

## Definition of Done

- 代码与 Migration Review 完成；
- Unit、Integration、Contract、E2E 适用测试通过；
- 权限、审计、日志和 Metrics 完成；
- Raw Traceability 与 Data Quality 检查完成；
- 文档、Runbook 和 Release Note 更新；
- Key User 使用真实或脱敏真实数据验收；
- 无未登记的妥协实现或 Deferred Item。

# 风险、假设与决策台账

| ID | 风险/假设 | 影响 | 控制措施 |
| --- | --- | --- | --- |
| R-01 | 平台 API 频繁变更或废弃 | 同步失败、字段丢失、错误决策 | Capability Registry、Release Check、Contract Test、Raw Replay |
| R-02 | 历史数据口径不一致 | 利润和趋势不可比 | Data Profiling、Metric Version、Backfill Manifest、Confidence |
| R-03 | SKU Mapping 错误 | 库存、成本和退货错误归因 | 人工确认、冲突队列、版本和重算 |
| R-04 | 采购成本不完整 | 伪利润或错误放量 | 阻断 Settled Profit，显示缺失并建立成本治理 |
| R-05 | 迟到退货/费用 | 历史利润漂移 | 滚动回看、Adjustment、Close Version |
| R-06 | 自动写操作失控 | 价格、库存和广告损失 | 默认关闭、Shadow、Approval、Guardrail、Kill Switch、Readback |
| R-07 | 运营团队不采用 | 系统变成无人看的报表 | Task-first、Key User 共创、使用指标和每周 Review |
| R-08 | 开发过度复杂化 | 交付慢、维护成本高 | Modular Monolith、Phase Gate、Non-goals |
| R-09 | Secret 或个人数据泄漏 | 账号、法律和声誉风险 | Secret Manager、最小化、访问审计、俄罗斯本地存储审查 |
| R-10 | 把第三方估算当真值 | 错误采购或广告 | E 级 Confidence、禁止自动触发、高风险决策需官方/内部证据 |
| R-11 | 平台数据延迟被误认为实时 | 错误暂停或加预算 | Freshness、更新周期、Data Quality Gate |
| R-12 | 财务系统与运营系统边界不清 | 重复记账或责任冲突 | 明确 Operational vs Statutory Accounting，接口和签审边界 |

## 已固定决策

- D-01：双平台架构，Ozon 先端到端，WB 首期并行 Read Integration；
- D-02：首版不开放无人审批写操作；
- D-03：采用 Modular Monolith + PostgreSQL Worker；
- D-04：Raw、Inventory Ledger、Financial Ledger 不可变；
- D-05：Variant/Color/Size/Purchase Batch 是必要经营粒度；
- D-06：第三方竞品数据只作趋势；
- D-07：AI 只建议，不持有平台写权限；
- D-08：官方 API 是唯一允许的程序化平台接入方式；
- D-09：Metric 和 Mapping 必须版本化；
- D-10：Phase Gate 未通过不得扩大自动化。

## Sprint 0 必须关闭的开放问题

- 实际 Ozon/WB 履约模式与 Store/Account 数量；
- 平台账户可用的 API Role、Subscription 和广告权限；
- 采购成本、包装、仓库人工、税费和 Overhead 的首版口径；
- 订单完成、拒收和退货的业务窗口；
- 内部 Barcode / SKU 数据质量；
- 是否已有 ERP/WMS/会计系统及数据接口；
- 俄罗斯部署、备份、个人数据和跨境访问的法律确认；
- 运营团队的 Hero SKU 和第一轮实验范围。

# 验收与上线 Gate

## Phase 0 Acceptance

- [ ] 全部 Marketplace Account、Store、Warehouse 和 Owner 已登记；
- [ ] Secret 不在 Git、日志、前端或普通配置中；
- [ ] 活跃 Variant Mapping 达到 agreed threshold，未映射有明确队列；
- [ ] Ozon/WB 历史数据有 Backfill Manifest 和 Hash；
- [ ] Raw → Core Traceability 可验证；
- [ ] 重放同一 Batch 不重复；
- [ ] Daily Report 能按平台和 SKU 展示订单、库存、退货和成本状态；
- [ ] Data Quality Dashboard 可见 Freshness、失败和缺口；
- [ ] API 限流、重试、告警和人工恢复通过；
- [ ] 业务 Key User 完成真实数据 Review。

## Phase 1 Acceptance

- [ ] Closed Day 订单行与平台来源达到目标一致率；
- [ ] Ozon Product/Order/Stock/Return/Finance/Ads 同步稳定；
- [ ] WB Read Integration 支持统一平台对比；
- [ ] Operational Profit 可追溯，缺失成本不被隐藏；
- [ ] SKU Profit、Funnel、Inventory、Return、Ads 页面可用；
- [ ] 重大异常在 1 个运营日内进入 Task；
- [ ] Freshness 和 Confidence 在所有核心页面可见；
- [ ] 运营团队连续使用并提供反馈；
- [ ] 生产写 Feature Flag 仍为关闭或仅限已批准测试账户。

## Controlled Write Capability Gate

每一个写 Capability 独立通过：

1. 官方权限与 API 使用规则已验证；
2. Contract Test 与真实受控账户测试通过；
3. Recommendation 与 Guardrail 已稳定；
4. Shadow Mode 与人工结果比较达到标准；
5. Approval、Expiry、Entity Version Check 完成；
6. Outbox、Idempotency、Retry Budget 完成；
7. Timeout/Unknown Result 的 Readback 与人工处理完成；
8. Kill Switch 可在分钟级关闭；
9. Audit 和 Metrics 完整；
10. Owner/Operations/Security 明确批准开放范围。

## Production Go-live Gate

- [ ] Security Review 通过；
- [ ] Personal Data / Russia Hosting Review 完成；
- [ ] Backup、PITR 和恢复演练通过；
- [ ] Runbook 与 On-call 责任明确；
- [ ] 关键 Dashboard 和 Alert 已验证；
- [ ] Migration、Rollback、Feature Flag 计划批准；
- [ ] No Critical / High 未关闭缺陷；
- [ ] Requirement → Test → Evidence Traceability 完整；
- [ ] 业务操作培训完成；
- [ ] 上线后 72 小时观察计划明确。

# 业务输入清单

IT 团队开工不应等待所有资料完美，但以下输入必须进入受控清单并持续补齐。

## 账户与组织

- 法律主体、税务/会计责任人；
- Ozon/WB Store、Seller Account、Owner；
- FBO/FBS 或平台对应履约模式；
- 本地仓库、地址、时区和作业时间；
- 当前用户、外部服务商和权限。

## 商品

- Internal SPU/SKU；
- Barcode；
- 颜色、尺码、俄罗斯尺码映射；
- Ozon Offer ID/SKU；
- WB nmID/chrtID/Barcode；
- 类目、属性、图片、标题和描述；
- Supplier、Purchase Batch、采购日期和成本；
- 包装、整备和质量信息。

## 交易与运营

- 90～180 天订单、取消、发货、退货和退款；
- 平台库存和本地仓库库存；
- 广告 Campaign、Spend 和统计；
- 促销、折扣和价格历史；
- 评价和退货原因；
- 当前人工报表和 KPI。

## 财务

- 平台结算、Realization、费用和赔付；
- 采购成本、物流、包装和仓储成本；
- 税费估算口径；
- 银行或会计系统可对账数据；
- Soft/Hard Close 责任和周期。

## 业务规则

- 最低贡献利润和最低价格；
- Hero/Growth/Repair/Exit 标准；
- 库存安全天数和补货提前期；
- 广告 Allowable CPA / Max CPC 的目标；
- 退货率、缺货、滞销和异常阈值；
- 高风险操作审批矩阵。

# Appendices

## Appendix A：首版 Daily Operations Brief 模板

```text
日期 / 数据窗口：
数据健康：Ozon / WB 最后同步、缺口、Confidence

一、昨日经营
- Orders / Completed Sales / GMV / Net Sales
- Operational Contribution Profit
- Settled Profit（如已可用）
- Return / Cancellation

二、需要立即处理
1. 负利润 SKU
2. 缺货风险
3. 履约超时
4. 高退货 / 质量异常
5. 数据阻塞

三、增长机会
- 可扩大 Hero SKU
- 有曝光无点击
- 有点击低转化
- 广告可优化项

四、待审批
- 价格 / 库存 / 广告 / 促销

五、昨日行动结果
- 完成任务
- 实验变化
- 指标改善或恶化
```

## Appendix B：Recommendation Schema

```json
{
  "recommendationId": "rec_...",
  "type": "LOW_CTR_MAIN_IMAGE_TEST",
  "subject": {"type": "LISTING_VARIANT", "id": "lv_..."},
  "problem": "...",
  "facts": [{"metricRef": "...", "value": "...", "confidence": "C"}],
  "inferences": ["..."],
  "proposedActions": ["..."],
  "expectedImpact": {"metric": "CTR", "direction": "UP"},
  "risks": ["..."],
  "guardrailResult": "PASS",
  "validUntil": "...",
  "ownerRole": "MARKETPLACE_OPERATOR"
}
```

## Appendix C：官方资料核验基线（截至 2026-08-06）

以下官方资料用于确认当前 API、Credential、更新频率和合规设计。开发团队必须在每个 Sprint 重新核验，不应把本文的日期事实永久硬编码：

1. Ozon for Developers — Seller API：`https://dev.ozon.ru/start/40-Seller-API-API-prodavtsa-dlia-raboty-s-marketpleisom/`
2. Ozon — Seller API 角色与方法映射：`https://dev.ozon.ru/start/300-Mapping-rolei-i-metodov-Seller-API/`
3. Ozon — Seller API Key 新规则（180 天）：`https://dev.ozon.ru/start/454-Novye-pravila-raboty-s-kliuchami-v-Seller-API/`
4. Ozon — Seller API OAuth：`https://dev.ozon.ru/start/450-Protsess-OAuth-avtorizatsii-dlia-dostupa-k-Seller-API-Ozon/`
5. Ozon — 财务报告方法更新：`https://dev.ozon.ru/news/699-Novye-metody-dlia-finansovykh-otchetov-v-Seller-API/`
6. Ozon — API 合规与避免封禁：`https://dev.ozon.ru/start/298-Seller-API-kak-izbezhat-blokirovok/`
7. Wildberries — WB API Documentation：`https://dev.wildberries.ru/en/docs/openapi/api-information`
8. Wildberries — Authorization System：`https://dev.wildberries.ru/en/knowledge-base/articles/019d49a1-0d73-71e9-be3e-b2c44567470c/wb-api-authorization-system`
9. Wildberries — Analytics API：`https://dev.wildberries.ru/en/docs/openapi/analytics`
10. Wildberries — WB API Digest June 2026：`https://dev.wildberries.ru/en/news/324/wb-api-digest-june-2026`
11. Roskomnadzor — Personal Data Localization Overview：`https://pd.rkn.gov.ru/docs/Obzor_po_lokalizacii.docx`
12. Roskomnadzor — Cross-border Transfer Notification：`https://pd.rkn.gov.ru/cross-border-transmission/form2/`

## Appendix D：启动授权模板

```text
Owner 正式授权启动：
Russia Marketplace Operations & Decision Platform
Phase 0 — Data, Identity and Operational Visibility Foundation

本阶段目标不是开发通用 SaaS，不是建设一次性 Dashboard，也不是立即实现无人化运营。

本阶段必须基于俄罗斯本地经营主体当前拥有的 Ozon、Wildberries 店铺、本地仓库、采购成本和履约数据，建立可长期演进、生产级、可审计的 Marketplace 运营与决策平台基础。

固定边界：
1. 架构同时支持 Ozon 与 Wildberries；
2. Ozon 作为首个端到端实施平台；
3. Wildberries 完成 Read Integration、SKU Mapping 和统一分析；
4. 平台写操作默认关闭；
5. 采用 Modular Monolith；
6. Raw Data、库存 Ledger、财务 Ledger 不可变；
7. 所有指标有来源、时间和 Confidence；
8. 不得使用未公开接口、爬虫或模拟人工页面操作；
9. 不得用第三方竞品估算替代官方或内部事实；
10. Phase 0 通过 Owner/Controller Review 后方可进入下一阶段。
```

## Appendix E：开发团队的第一周任务清单

1. 任命 Product Owner、Tech Lead、Ozon/WB Key User、Finance 和 Warehouse Key User；
2. 举行领域 Workshop，固定商品身份、订单状态、库存流水和利润口径；
3. 建立 Account/Credential Register；
4. 拉取官方 API 文档并创建 Capability Matrix；
5. 对 90～180 天数据进行样本导出和 Data Profiling；
6. 建立 Internal SKU 与平台 ID 初始映射；
7. 固定 Raw、Core、Ledger、Mart、Ops Schema；
8. 创建代码仓库、CI、Flyway、Secret 和 Observability 基线；
9. 完成 Ozon/WB 第一个只读 Connector 与 Raw Archive；
10. 交付第一个可追溯 Daily Business Report，而不是仅交付 UI Mockup。

## Appendix F：文档拆分建议

本文件批准后，技术团队按以下层级继续细化：

```text
Baseline v1.0（本文件）
  ├── Product Backlog / Epic & Story
  ├── Domain Model & Data Dictionary
  ├── Platform API Capability Matrix
  ├── Architecture Decision Records
  ├── Module Design Specification
  ├── Internal OpenAPI Specification
  ├── Database ERD & Flyway Plan
  ├── Metric Definition Register
  ├── Security & Data Protection Design
  ├── Test Strategy & Traceability Matrix
  ├── Deployment / Observability / Runbook
  └── Phase Acceptance Evidence
```

所有子文档必须引用本文件的 Requirement ID 和 Owner Decision，不得形成相互矛盾的平行需求源。

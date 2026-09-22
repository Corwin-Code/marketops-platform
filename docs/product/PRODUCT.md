# MarketOps Russia 产品概览

## 产品定位与目标用户

- 服务一个俄罗斯经营主体（多店铺、多仓库、多用户）的内部运营与决策系统；不是对外 SaaS，也不替代 ERP/WMS/会计。
- 统一 Ozon、Wildberries 官方数据与内部成本/库存/财务事实，找出需要处理的 SKU/店铺、解释原因、给出具体动作，并在规则与人工审批下执行平台操作、回读验证。
- 核心闭环：`Fact → Diagnosis → Recommendation → Governed Action → Readback → Outcome`。
- 用户：Owner、运营、财务；经外部 OIDC + MFA 远程登录，业务角色与 Store/Platform/Warehouse 数据范围由 MarketOps 管理。
- 衡量标准是帮助真实运营提升销售和 Contribution Profit，而非页面或功能数量。

## 产品 1.0 范围（2026-09-22 确认）

- 首发业务范围：Slice 001–004；Ozon 优先，Wildberries 后续交付。
- 接入 Qwen，交给真实运营使用，不以演示数据界面为终点。
- Slice 005–007 后续交付，不取消。
- 1.0 不宣称完整 V1（完整 V1 需全部 Slice 集成，且 Ozon 与 WB 各至少启用一个受控写能力）。
- WB 后置不删除已有平台隔离与实现；001–004 用到的订单、退货、费用、成本、库存事实继续保留。

## V1 全部 Slice 一览

| # | Slice | 内容 | 写能力目标 | 版本 |
| --- | --- | --- | --- | --- |
| 001 | SKU Growth & Profit Diagnostic Loop | 跨域 SKU 诊断、AI 解释与建议、Task/Approval | `PRICE_CHANGE` | 1.0 |
| 002 | Stockout & Availability Risk | 缺货风险队列、确定性优先级、责任 Case 与结果验证 | 无 | 1.0 |
| 003 | Advertising & Traffic Efficiency | 投放效率与库存/利润联动；其余广告动作走人工 Manual Shadow | `AD_BID_CHANGE` | 1.0 |
| 004 | Promotion & Listing Conversion | Listing Health、转化测量、内容与促销候选、结果评估；促销仅人工执行 | `LISTING_DESCRIPTION_CHANGE`（仅俄语描述） | 1.0 |
| 005 | Order, Fulfillment & Return Control | FBO/FBS 订单状态、SLA、退货/QC 与异常 | 低风险订单动作（未定） | 后续 |
| 006 | Finance & Contribution Profit Reconciliation | Operational/Settled 利润、迟到调整、对账关账 | 默认无 | 后续 |
| 007 | Cross-domain Command Center | 每日指挥台、Policy 管理、跨域优先级 | 仅扩展已验证能力 | 后续 |

## 平台与数据来源

- 只用 Ozon、Wildberries 官方 API 与报表；禁止爬虫、浏览器自动化和未公开接口。
- 内部事实：COGS（Cost Version）、本地实体库存、必要财务输入；经受控手工录入与 Excel/CSV 导入（校验、预览、映射、审批）进入。
- 统一内部 Capability 模型，平台差异封装在 Adapter；两平台 FBO/FBS 分别建模。
- 端点、权限、配额、回读语义须经官方文档与真实账户核验；未核验能力保持 `UNVERIFIED` 并关闭。
- 目标生产环境：Yandex Cloud `ru-central1`。

## AI 的角色与边界

- 用途：发现并排序异常/机会、跨域关联、提出竞争性根因假设、说明不确定性、给出具体动作与预期效果。代码中用于 SKU 诊断解释（`AiDiagnosisService`）和 Listing 辅助（假设比较、俄语描述草稿、促销）。
- 输出区分 `Fact` / `Inference` / `Recommendation` / `Unknown`，附证据引用。
- 不计算官方指标或利润，不持有平台 Credential，不能审批或执行命令，不能绕过规则校验；AI 生成的俄语内容须经授权人员审核才能发布。
- 经 provider-neutral `ModelGatewayPort` 调用外部模型，只发送字段白名单、脱敏数据；模型不可用时仅解释降级，确定性诊断不受影响。

## 贯穿全产品的规则

- 金额用 decimal + 显式币种。主利润口径 `Contribution Profit` = Net Sales − COGS − 佣金 − 履约/配送/仓储 − 退货损失 − 广告与促销 − 变动税估算 − 其他变动费用；分 Canonical（已确认输入）与 Estimated（带 Confidence 与假设）。
- `Completed` / `Retained` / `Settled Sale` 分开；Retained 支持 7d/14d/30d，默认 30d。
- Raw 不可变：原始字节、请求元数据、hash、时间与来源永久保留；重放不产生重复效果；库存与财务 Ledger 只追加，迟到事件生成调整和新计算版本；未知状态显式保留并 fail-closed。
- Freshness/Confidence 按数据域区分，并作为建议与写入的前置条件。
- 写平台链路：证据化建议 → 确定性规则 → Dry Run → 人工审批或 Owner 预授权 Policy → 幂等 Command/Outbox → 官方 Adapter → Readback → 成功/不一致/未知人工处置 → Audit、恢复/补偿、结果跟踪。写能力默认关闭，按平台+店铺+能力+实体范围启用，各有 Kill Switch；超时不盲目重试；高风险动作逐单审批。
- 买家姓名/电话/地址和 Secret 不进入 AI 与通用分析；敏感操作全部审计。

## 当前状态与下一步

- 后端：Java/Spring Boot 模块化单体（`analyticsdecision`、`operatingfacts`、`operationsworkflow`、`availabilityrisk`、`advertisingefficiency`、`listingconversion`、`marketplaceintegration`、`aicopilot`、`identityaccess` 等），PostgreSQL + Flyway。
- 前端：React + TypeScript 控制台，含诊断、优先级队列、建议审阅、命令时间线、可用性、广告、Listing 页面与 OIDC 登录；未使用 UI 组件库。
- Slice 001–004 的代码均已在 main（004 经 PR #35 合入）。
- 未接入真实 Ozon/WB API 与真实 LLM：调用规格由登记数据驱动，目前无已核验规格，只用合成数据和脚本化响应；生产写入全部关闭。
- 下一步：接入 Qwen；分阶段接入 Ozon 真实读取 API（WB 后置）；用 Ant Design 按基础组件 → 独立区块 → 全局布局重构 UI；结合真实场景完善 001–004 并发布 1.0。

## 待决问题

- 1.0 启用哪些 Ozon 写能力，还是先以读取 + 建议 + 人工执行留痕上线。
- Qwen 的服务商、地域、模型、数据出境边界与费用。
- 运营范围：店铺/SKU、代表性样本、验收阈值与首批试点。
- 初始商业阈值：最低利润、最大调价幅度、冷却期、Confidence。
- 内部 COGS/库存/财务表格的实际结构与责任人。
- 部署环境与运行权限；俄罗斯托管与个人数据合规确认。

# Slice 004 — Promotion & Listing Conversion

## 目标
- Operations Lead 在利润、库存、退货约束内，对已有商品卡选一项主要改变（俄语 Description 改写或简单促销），经审核、批准、执行、核验后评价效果。
- 主指标 `|S|/|V|`：V=合格商品卡访问，S=其中至少一次合格 Retained Sale 的访问；零分母 `UNDEFINED`，资格缺失 `NOT_AVAILABLE`，不填零。订单、Completed Sale 仅为先行指标。
- 不承诺增长；不做标题/图片/属性写、复杂促销、促销 API、需求预测/调价、爬虫或后台自动化。

## 用户与角色
| 角色 | 职责 |
|---|---|
| Operations Lead | 端到端经营负责；普通动作最终批准 |
| Owner | 重大动作最终批准；接受校准包 |
| 运营员 / 执行人 | 准备动作；执行人工包并填报告 |
| 专业审核人 | 独立于作者，审核俄语全文与事实 |
| Risk / Tech | 按范围关闭（containment）并提交修复证明 |

作者不能自批；技术管理员和 AI 都没有商业批准权。

## 核心流程
1. **Listing Health**：三层——必要条件（硬问题不可抵消）、按用途的证据资格、改善机会；无总分，Unknown≠失败。必要条件失败生成诊断责任 Task。
2. **诊断与候选**：候选类型为 `CONTENT_DESCRIPTION`、`OFFICIAL_PROMOTION_PARTICIPATION`、`SELLER_DIRECT_DISCOUNT`，每轮只选一个主要改变。用途分四种：`LISTING_CONVERSION`（正式改善，需冻结评价计划）、`DESCRIPTION_CORRECTION`、`BOUNDED_EXPLORATION`（只走人工、有期限）、`PROMOTION`。
3. **完整影响集合**：`affected set` 冻结原生 Listing/Variant 全集及映射版本 digest；UI 选一个 Variant 不缩小范围；不完整只阻断需要完整性的用途。
4. **Materiality**：两个独立轴——内容含义（审核人逐条回答校准包条件 APPLIES/DOES_NOT_APPLY/UNKNOWN）与经营暴露（保留净销售占店铺比例）。任一轴重大 → Owner 批准；介于上下界 → 未定，停在 DRAFT。
5. **促销模拟**：`PromotionSimulator` 用同一利润口径做正向情景和最低销量反算（`COMPUTED`/`NO_SOLUTION`/`UNDETERMINED`）；阶梯费用取已达最高档；已含于净收入的卖家折扣不重复扣；缺失费用保持未知。必要保守情景各自通过才算 `QUALIFIED_CONDITIONAL_ECONOMICS`。反算不是预测。
6. **准备 → 审核 → 批准**：绑定原生对象、affected set、路径（`MANUAL`/`API`，批准前选定）、当前与目标俄语全文 digest、评价计划、校准版本；有效期取最早失效，任一依据变化即失效。
7. **启动与累计额度**：批准不会发送。授权人员一次启动（需 step-up），系统复核绑定、利润/退货/供给保护、containment 和 Guardrail，再占用多轴额度（各轴独立，留处置余量）；不足 → `APPROVED_NOT_LAUNCHABLE`。API 启动与唯一的 Description Command 同一事务提交。
8. **Description API 路径**：Command 须过写 Gate（能力已验证、kill switch `listing-description-write`、allowlist、批准/启动/占用、非目标字段、`kizMarked`、长度）。平台接受、`READBACK_MATCHED`、顾客展示分别记录；`UNKNOWN_REQUIRES_READBACK` 只回读不盲重试。
9. **恢复**：新动作，目标为捕获的非空原文，需重新审核批准；原文为空则 `RESTORE_UNSUPPORTED`，不写占位文本。
10. **人工路径**：启动后签发人工包 → 执行人报告（不算核验）→ 另一人或官方证据独立核验，管理侧与展示分开。两类简单促销只走此路径；退出须分别证明“停止新交易”和“残留义务清零”，各释放对应轴。
11. **评价**：计划冻结节点窗口、方法（如 `EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1`）、关键群组和可选停止规则；节点给出 `MET`/`NOT_MET`/`UNDETERMINED` 及保护向量（PASS/FAIL/UNDETERMINED，互不抵消）；Operational/Settled 分开，迟到事实追加修订。
12. **校准包**：专业人员准备并验证，Owner 独立接受并激活；按用途要求参数类别；缺失为 `CALIBRATION_UNRESOLVED`，无代码默认值。
13. **运营支撑**：责任时钟（承接与处置分开计时，转派不重置）、重算队列（RISK 5 / ORDINARY 15 / FULL_REVIEW 60 分钟）、日/周复盘、反馈主题、经验复用、有限批次（逐项批准启动）。

## 业务规则与安全控制
- 平台写必须先有批准、确定性 Guardrail、一次启动、幂等 Command、Readback 与审计；kill switch/containment 可按范围关闭。
- 重新启用需两人：修复证明与业务同意；关闭不等于平台旧动作已撤销。
- 额度只凭证据释放（`STOP_EVIDENCE`/`OBLIGATION_CLEARED`/`NOT_APPLIED_PROVEN`/`OUTCOME_MATURED`），单纯时间流逝不释放，Unknown 继续占用；人工与 API 共用额度。
- `OUTCOME_MATURED`（描述修改的效果观察期满）：描述动作已应用且已确认（人工路径：合格的独立核实使动作进入 `VERIFIED`；API 路径：Readback 与目标一致的 `MANAGEMENT_VERIFIED` 执行回执），且自确认时刻起已满该动作校准包 `RESPONSIBILITY_SLO.outcomeMaturityDays` 天，才释放其全部 `ACQUIRED` 占用。存在更晚的相反核实（与先前一致/不一致/未展示）、处于 containment（含未解除的结果失败）、有未关闭的不明变更调查或越权偏差、该值缺失或非法（须为 1–3660 的整数，缺失时不回退任何默认值）、或校准包无接受人时，一律不释放。促销占用不走此路径。释放由默认关闭的定时任务（`marketops.listing-conversion.allowance-release.enabled`）或本机维护入口触发，幂等；`released_by_user_id` 记为该校准包的接受人（Owner 预授权策略），审计记为系统组件 `listing-allowance-release`。
- 金额为 Decimal + 明确币种，不做 FX；Raw 与历史结论不可覆盖，只追加修订。
- 发起人只见最小授权视图，财务明细需对应权限。
- 中俄双语，桌面端；俄语原文不自动翻译。

## 数据与平台接口
- 主要表（`core/ops/mart.lc_*`）：`affected_set`、`description_observation`/`display_observation`、`visit_fact`/`visit_purchase_link`、`official_summary_observation`、`calibration_package`、`candidate`、`action`/`action_review`/`action_binding`、`evaluation_plan`/`node_result`/`outcome_revision`、`exposure_allowance`/`occupation`、`manual_packet`、`promotion_engagement`、`description_command`、`containment`、`batch`、`recalculation_queue`、`listing_health`。
- 证据路径：`DETAIL`（访问明细）或 `SUMMARY`（官方汇总，需 equivalence profile 为 `PROVEN`）。
- 候选平台接口（据官方文档，未实际调用）：
  - Ozon：`/v1/product/attributes/update`（返回 `task_id`）→ `/v1/product/import/info`；回读 `/v1/product/info/description`；指标 `/v1/analytics/data`。
  - WB：`/content/v2/cards/update`（整卡覆盖，须保留非目标字段与 `kizMarked`）；Sales Funnel、Order Feed、财务明细。
  - 限流头：Ozon `Item-Retry-After`（分钟）、WB `X-Ratelimit-Retry`（秒）。

## AI 的使用
- 经现有 `aicopilot` Gateway（入口 `ListingAssistanceService`）。用途：`HYPOTHESIS_COMPARISON`、`RUSSIAN_DESCRIPTION`、`SIMPLE_PROMOTION`、`REVIEW_SUMMARY`。
- `RUSSIAN_DESCRIPTION` 只依据已有商品事实提出俄语措辞，缺失事实标 Unknown；草稿仅供人审核，正式正文仍走准备 → 独立审核 → 批准。
- 输出分为 Fact / Inference / Recommendation / Unknown；AI 不算利润、不定阈值、不宣称批准或因果；只能读取白名单字段，没有 Credential，也不能执行。
- 模型不可用时可人工准备。

## 已实现 / 未实现
**已实现（代码中存在）**
- 后端：`listingconversion` 模块（约 95 个类，如 `ListingHealthService`、`MaterialityClassifier`、`PromotionSimulator`、`EvaluationService`、`ManualPathService`）；`operationsworkflow` 的 `ListingActionIntake`/`ListingActionLaunch`；`marketplaceintegration` 的 `ListingDescriptionCommandWorker` 与写 Gate。
- 前端 `src/listing/`：Health、Actions、Meaning Review、Promotion Terms、Manual、Governance、Assistance、Feedback、Operations Review、责任时钟面板。
- API：`/api/v1/console/listing/*`、`/api/v1/console/listing-description-commands`。

**未实现 / 未接入**
- 未接入真实 Ozon/WB API：Description 能力未验证，adapter 在连接前拒绝；worker 全部默认关闭；仅用合成数据、fake provider 和本地 loopback。
- LLM 已接入阿里云百炼 `qwen3.8-max`（见 `docs/development.md`）。
- 两平台“成功保留访问”的同义数据路径、真实校准参数、人员覆盖均未确认。
- 尚未确定：Ozon 正文属性 ID、WB 整卡写的并发保护。
- 不在本 Slice 范围：手机专用审批、邮件通知、专用导出包。

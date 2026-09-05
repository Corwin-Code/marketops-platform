# SLICE-V1-003 — Final Closure Verification R1

**裁定：NOT PASS — 原冻结 Findings 尚有未闭合的实现与证据。**

本次核验对象为 PR #30 的准确 Head `3ff042df66d5d6924b587cac96fc652b93bf5e7a` / tree `907aa20d6d38e116db9cddd9e54ded87a99cbe64`。没有修改 GitHub，没有新建 Finding Set，没有修改 Contract，没有 Ready、merge、部署或 Provider 操作。

| 项目 | 结果 |
| --- | --- |
| Base / Starting Head / Final Head 关系 | 远程 compare 显示从 reviewed Starting Head 追加12个提交，behind=0 |
| Tested merge | `dddb7584b7930b833379f2a3ac75875df05cde0c`，父提交为准确 Base + Final Head，tree 等于候选 tree |
| PR | Draft / Open / Unmerged，mergeable 不等于获准合并 |
| 必需 CI | Ruleset 20734984 的12个 context 全部成功；聚合 CodeQL 成功 |
| 交接完整性 | 67/67 manifest成员通过；40/40原始tar成员与提取文件一致；7/7 artifact ZIP SHA匹配live GitHub digest |
| 原 Contract / Finding Set / Owner授权 | 本地重算 SHA与远程 Git blob 一致 |
| 后端 CI | 189份报告、2484 actual testcase nodes、0 failure/error/skipped；单独924个integration节点是重复运行，不再加到2484 |
| 前端/治理/基础设施 | 日志对应327 unit、25+12 browser、418治理、22+7基础设施；对应live jobs成功 |
| Finding关闭 | 17项已提交的关闭证据可在本次范围内接受；4项根因/范围证据未闭合；1项关闭索引依赖这些残留 |
| AC200 | Controller独立结论未通过 |
| 生产 | 24项REL仍阻断；production_write_enabled=false |

## 1. 核验边界

本轮从 live GitHub 读取 PR、tested merge、compare、check runs、Ruleset 和 artifact metadata；对上传包内原始 ZIP 重算 SHA并核对来源，解析实际 XML testcase 节点、JaCoCo根计数及容量 receipt，而不是只接受 handoff 的摘要。

后端 artifact 的 JaCoCo HTML 包含 exact source。解码后对 matrix 所列的66个 Java 文件重算 SHA全部一致；决定本次未通过的源码又以 exact-ref GitHub read复核。本轮执行了两个小范围 Java诊断：一个直接编译未修改的生产 domain代码，另一个仅 replay精确生产 Spend分级表达式。

**没有重新运行全部 MarketOps Maven、PostgreSQL、npm和浏览器套件。** 这些套件的成功来自经digest校验的远程CI证据。两个小诊断不被包装为完整应用/SQL/Provider集成测试。远程读取不能验证Codex当前本地worktree；clean是其交回声明。

Final Gate只核验原22项的根因、传递影响、测试/证据及最终Contract符合性。本报告的CV-A..E是核验检查编号，不是新的Finding Set或新增需求。

## 2. 未闭合项

### CV-A — Outcome 的官方 Spend 分级未消费所需的来源/采集年龄与用途 Freshness 权威

原 Finding：`S3-DR-004`, `S3-DR-015`。

**实际证据：** objectFacts 返回 source/accepted 时间；Outcome service 仅以金额非空、窗口完整、correction 关闭将 Spend 标为 CANONICAL_CONFIRMED。用途 Profile 在该分支只处理 null/Provider incident，没有把官方 Spend 的年龄、覆盖和独立 evidence-kind 的用途规则传入分级。

**验证范围：** 精确生产 Spend 分级表达式的独立 replay；不是完整 Spring/PostgreSQL 集成测试。

**结果：** 同一 complete/closed 报表，1秒和3540秒 age 在示例900秒已发布边界下都得到 CANONICAL_CONFIRMED / sufficientForWrite=true。

**关闭条件：** 对 Outcome 每项必需输入解析并执行 frozen/适用的 evidence-kind × scope × purpose Freshness；历史 cohort 成熟不自动 stale，但超过本用途明确期限不能自动 confirmed。加入旧 source/new ingestion、窗口不全、版本撤销、Provider incident、合法成熟窗口等正反集成测试，证明不能形成伪成功或伪 Exposure-Stopped。

### CV-B — Cause-bound Protection 被实现内固定为两个原因，精确 Policy 不能授权已证明经济伤害的同一模式

原 Finding：`S3-DR-004`, `S3-DR-011`。

**实际证据：** causeBoundProtectionQualified switch 只接受 NOT_SELLABLE 与 UNAVAILABLE；PROVEN_ADVERTISING_LOSS 不论 exact Policy 如何配置都返回 false。Proposal 随后只能走 unavailable Max-CPC 路径；SQL 的 cause/evidence 列表同样只含两个原因。

**验证范围：** JDK 21 直接编译未经修改的、CI artifact digest 绑定的生产 domain 源码；未模拟替换实现；不是完整网络/数据库测试。

**结果：** 同一完整对象、Current Bid、正 Spend、无 Max CPC、Fresh purpose evidence：UNAVAILABLE=true、NOT_SELLABLE=true、PROVEN_ADVERTISING_LOSS=false；三者方向均为 PROTECTION_DECREASE。

**关闭条件：** 在 exact Policy 明确接受 PROVEN_ADVERTISING_LOSS、Fresh 完整经济证据证明未知 conversion 不会翻转危险且其他 Guard 均满足时，使用同一可解释 Cause-bound 模式，或给出能够证明已接受 Contract 不适用的精确证据。不得把未知利润当成亏损；不得免除销售/库存/权限/审批；Java、SQL、Planner、Preview 及测试需保持同一原因依赖规则。

### CV-C — 非财务 Protection 原因的 Risk-Cleared 终局仍缺独立原因验证路径

原 Finding：`S3-DR-015`。

**实际证据：** businessOutcome 对 VERIFIED_AD_RISK_CLEARED 只实现 PROVEN_ADVERTISING_LOSS + RETAINED + 非负利润。Availability/Sellability 只进入 confounder digest；其原始广告危险经 Fresh 证据解除时，不能独立得到相应原因终局，只能落入通用效率成功、Spend=0、Outcome Confounded 或仍在 Protection，不能独立完成原原因验证。

**验证范围：** 精确 source/control-flow 核验；本轮未重跑此场景的 PostgreSQL/Browser 测试。

**关闭条件：** 按原行动冻结的原因和观察窗口消费对应安全证据，分别证明原广告危险解除/新危险暴露停止/仍有伤害；与利润效率成功、库存或 Listing 本体修复严格分列。测试非财务原因解除而利润仍 unresolved 的情况，防止重新把不同责任合并。

### CV-D — 已通过的是限定的未启用对象编排容量，冻结 Finding 要求的活动 Outcome/控制状态负载证据仍未覆盖

原 Finding：`S3-DR-020`。

**实际证据：** 1000 UNVERIFIED native objects、200 synthetic containment objects、1200 Tasks，共享1个Store/Variant；测试断言 commands=0，未设置 mature Outcomes。Daily Brief 与拒绝路径被调用；活动 Outcome/完整控制状态的容量未被这份负载证明。

**关闭条件：** 保留当前 PASS。以已声明容量及代表性活动状态补充一个可重放 synthetic mixed workload，涵盖成熟/修订 Outcome、到期/失效等既定状态的 targeted update 与 hourly repair，报告实际阶段数量、P95/hard bound、全 sweep margin。无需真实 Provider、APPLY 吞吐指标、生产账户或新增多店铺规模目标；不降低现有阈值。

### CV-E — 关闭索引与最终运行数字需要随残留修复同步；旧 run 的容量数字不能冒充 W10

原 Finding：`S3-DR-022`。

**实际证据：** 22/22 和 AC001–199 的 blanket CLOSED/VERIFIED 不能覆盖 CV-A..D。PR/摘要的30789ms、109169ms与两个实际 W10 artifact receipt 不一致；最终 artifact 自己的 run/head/merge 绑定与数值可信，应分开保留旧测量来源。

**关闭条件：** 只在实际根因和证据闭合后重生成矩阵。逐测量记录 sourceHead/testedMerge/run/job/artifact/dataset；保留历史数字但标注其原 measured source。所有既有独立通过证据继续保留，不改 Contract 或 Frozen Finding Set。

### 原因边界说明

CV-B不是要求“只要亏损就自动降Bid”。待验证场景必须已有Fresh且完整的单向经济危险证明、完整对象/affected-set、未失败销售Guard、无有效Exception、显式接受该cause的Target Policy和完整审批；缺少任一项仍应拒绝。问题是代码在这些条件之前固定排除了该原因，Policy没有决定机会。

CV-C也不是要求广告模块修复库存。它要求将“原广告风险解除”与“库存/Listing本体修复”“主效率成功”分开验证。当前只把可售/库存变化放入confounder digest，不能替代原原因观察。

CV-D不新增多店铺、真实Provider或APPLY吞吐目标。冻结S3-DR-020已经要求在声明容量下覆盖计算、投影、Task、brief/outcome和write-gate state。当前PASS有效，但只证明所声明的零命令/零成熟Outcome拓扑；没有证明的部分不能被全量VERIFIED覆盖。

## 3. 22项关闭核验台账

| Finding | 原级别 | Controller本轮状态 | 检查引用 |
| --- | --- | --- | --- |
| S3-DR-001 | MAJOR | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-002 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-003 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-004 | BLOCKER | 根因未完全闭合 | CV-A, CV-B |
| S3-DR-005 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-006 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-007 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-008 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-009 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-010 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-011 | BLOCKER | 根因未完全闭合 | CV-B |
| S3-DR-012 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-013 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-014 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-015 | BLOCKER | 根因未完全闭合 | CV-A, CV-C |
| S3-DR-016 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-017 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-018 | BLOCKER | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-019 | MAJOR | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-020 | MAJOR | 容量范围证据未完整 | CV-D |
| S3-DR-021 | MAJOR | 关闭证据接受（本次范围） | Source + 已解析CI命名证明 |
| S3-DR-022 | MAJOR | 依赖残留，关闭索引不接受 | CV-A, CV-B, CV-C, CV-D, CV-E |

其余17项的接受不是一次生产授权，也不代表未来代码变动免于回归；它表示不把已经得到支持的修复重新作为普通开发任务派发。原始Frozen Finding Set的文字、哈希、22项编号均保持不变。

## 4. 两份真正的W10容量回执

摘要/PR中的30789ms、109169ms应保留原测量身份，不能替代以下actual W10 artifact内容。

| CI job / artifact | Critical P95 ms | maximum ms | targeted wall ms | full sweep ms | hourly margin ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| backend-build / 9974096071 | 14340 | 170587 | 169181 | 45353 | 3554647 |
| backend-integration / 9974152039 | 20799 | 194924 | 192914 | 69479 | 3530521 |

两次均满足各自已执行断言。它们使用4个JVM可见处理器、约4GiB最大JVM堆；这不是Docker配额的同义词，配额应看各run另行采集的resource receipt。两份数据集和job不同，不将数值拼成一个“更优”虚构run。

JaCoCo XML根计数为 LINE 23805/(23805+3861)=86.044242%，BRANCH7440/(7440+3019)=71.134908%；均高于80%/70%。Codex CSV按class汇总得到的line数多2行，属于统计口径差异，不改变Gate结果。不存在据此宣布覆盖率失败的理由。

## 5. 下一行动

Codex继续同一Owner授权、同一分支、同一Draft PR和原Frozen Finding Set的R1收口：

1. 先补Outcome逐来源/用途Freshness与原因特定Protection终局，再补精确Policy下的经济危险Cause-bound路径；Java/SQL/Planner/Preview/Outcome保持同一权威。
2. 为CV-A..C建立真实应用+isolated PostgreSQL的正反证，复用fixture Provider；不能仅把本报告的micro-probe包装为完整集成验收。
3. 保留已过容量证据，并补CV-D中已有Contract状态的代表性mixed workload，不降低门槛，不开启真实写能力。
4. 对影响面执行完整回归；同步CV-E中每份测量的确切来源和关闭矩阵。
5. append-only提交/push；PR保持Draft。交回新的Head/tree/tested-merge及12项必需CI和CodeQL，供同一Final Gate复核。

不需要重新问诊或新的产品Amendment。不得Ready、merge、force-push、修改Contract/Frozen Set、删除历史证据，或使用真实Credential/Provider/shared/production环境。只可重建明确拥有的disposable synthetic数据库。

## 6. 最终效力

```yaml
final_closure_verdict: NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED
ac200_controller_verdict: NOT_PASSED
engineering_closure: NOT_AUTHORIZED
formal_owner_closure: NOT_READY
approve_for_human_merge: NOT_ISSUED
new_frozen_findings_added: 0
contract_amendment_required: false
next_actor: CODEX
pr_must_remain: DRAFT_OPEN_UNMERGED
production_write_enabled: false
```

本报告不伪装为GitHub上的PR review/comment：Controller没有进行任何GitHub写操作。最终source修订后，应保留本报告作为被后续证据收敛的Final Gate历史，而不是改写它或原Frozen Finding Set。

# SLICE-V1-004 R1 root-cause rework design

Status: IN_PROGRESS. This is an evolvable implementation design, not an amendment or a Controller verdict. The exact starting identities and sole frozen input are in `docs/07-phase-evidence/SLICE-V1-004/rework-r1/TAKEOVER_RECEIPT.md`.

## Implementation boundaries

Keep the original Contract and annex byte-identical. Extend the existing identity/grant, canonical fact/metric, Policy/Approval, Task, Command/outbox and audit authorities. Existing migrations are immutable; the next migration is V0080. Use only isolated synthetic sources, fake providers and local HTTP/browser tests. No remote writes or real provider/account calls. Default provider writes remain disabled.

Work is continuous across the following dependent chains:

1. Current object/purpose authorization and financial projections; exact text preservation.
2. Independent detail/summary qualification, immutable input lineage, version windows, effective sale revisions and fixed comparison structure.
3. Frozen plan/method evaluation, independent protections/current safety, semantic/exposure classification and governed calibration publication.
4. Exact launch/allowance and promotion terms, purpose-bound release proofs, manual/display/deviation evidence and dependency-based containment.
5. One launch into the command outbox; new exact recovery authority; verified wire schema and asynchronous/retry semantics.
6. Durable recalculation and Task clocks, on-demand AI/feedback/reports, complete bilingual desktop journeys.
7. Whole regression, application-role attacks, concurrent/crash recovery, clean/upgrade migration verification, evidence/traceability/runbooks and final local checkpoint.

An implementation change is not closure until its full required observable correction and relevant regression are exercised. Domain tests, compilation, mock components and schema-shape checks are labelled at that granularity. The final artifact will identify the exact tested tree and local Head; no test result is inferred from earlier Slice evidence. Deferred real-world obligations remain at their original consuming gates.

## Frozen finding obligations

### S4-DR-R1-001 — Outcome写入口遗漏业务授权与对象边界，读权限检查不能覆盖写入口

在唯一应用权威上验证当前身份、组织、店铺及评价写权限，并把measurement绑定到准确行动、窗口和阶段；未授权请求必须零业务副作用且不泄漏EvaluationView。不是仅在按钮上隐藏。

Required evidence:

- 通过实际Console HTTP分别测试未登录、跨店、撤权、只读角色、不同对象measurement；拒绝后核对所有业务表/Task事件未变化。
- 同类扫描所有新增POST/GET与服务入口，避免修一个路由后其他入口仍可调用。

### S4-DR-R1-002 — 最小授权投影未落实到模拟明细与已发人工包的当前访问

查看与写入用途分开，明细按既有财务/Data Scope投影；列表与按ID读取均检查当前权限。保留既有合格导出控制，不新增本Slice专用导出。

Required evidence:

- HTTP角色矩阵校验成本、利润、关联受限字段不经列表/模拟/包入口泄漏。
- 撤权后旧包仍保留审计，但当前API不返回其受限内容；获准专业角色能够取得职责所需完整材料。

### S4-DR-R1-003 — 两条主指标证据路径被错误耦合，真实零购买与完整官方汇总不能独立成立

两路径分别证明同一语义、覆盖、成熟和修订资格；零购买和资料缺失明确区分。只能使用与证据profile和请求范围匹配的汇总，不裁小矛盾分子来生成比例。

Required evidence:

- 正常角色/来源入口证明独立DETAIL、独立等价SUMMARY及完整0购买均按定义计算。
- 不完整窗口、错summaryKind、重放/冲突分子、超界分子必须明确限用途，不伪造值。

### S4-DR-R1-004 — 版本覆盖和迟到销售修订未进入实际主指标计算，固定流量结构仍未实现

按事前窗口规则选择真实合格访问群组并保留过渡期全部经营责任；采用来源有效版本/as-of修订和固定可比流量结构；保留真正source/acquisition时间及输入引用。

Required evidence:

- 实际计算链验证排除仅影响适用效果口径、不删除风险/利润事实；跨时区和粗粒度完整窗口不做比例拆分。
- 后到退货/撤销使原受影响measurement与Outcome形成新版本；同一输入可重算，来源结构变化不能冒充提升。

### S4-DR-R1-005 — 正式Outcome以请求数字和绝对转化率生产通过，未消费合格改善及保护证据

正式Outcome必须来自绑定范围/时点/版本的合格改善与独立保护证据，缺失bound不得自动用点估计填补。Operational/Settled分别验证实际阶段；critical groups和关键Variant用自身冻结标准。确定性金额不强制制造统计区间，但比较/推断不得省略其资格。

Required evidence:

- HTTP到真实DB验证无改善但绝对率高、未给bound、任意保护数、伪SETTLED、错对象measurement不能获正式成功。
- 分别覆盖主目标达标/保护未定/已失败、关键组不恶化但不增长、关键Variant自身参照；保留真实未确定结果。

### S4-DR-R1-006 — 评价计划的节点、冻结政策和独立效果不足停止规则没有约束实际判定

评价按原计划/版本、允许节点、独立方法资格与有限期限运行；必要后到事实只修订原范围。效果不足分支是可选且需自身合格依据及既定人的决定，不是成功条件取反。无依赖的活动不强制附加后续窗口。

Required evidence:

- 冻结v1后激活v2，旧Outcome仍用v1、新安全用当前政策；提前/越界节点拒绝正式判定。
- P06反例不得触发停止；只有明确满足冻结futility条件才取得停止资格。无需stop/tail的合法计划可用。

### S4-DR-R1-007 — 启动Gate用结构性Listing Health代替利润、退货、供给与用途保障

把各领域唯一权威的必要保护与准确用途接入预览、批准及启动的当前Gate；硬失败不可被健康标签抵消。按已接受目的层次提供有限合法工作，不靠放宽全部Gate或伪造PASS。

Required evidence:

- 同一合格action在库存/利润/退货/需求证据变化后必须限域拒绝并说明原因。
- 正面证明安全纠错/有界人工探索与正式改善路径分别可达，缺主目标资料不自动扩大为全功能关闭。

### S4-DR-R1-008 — 普通/重大分类用字符差额和发起人暴露数字替代内容含义与真实经营暴露

按已接受的内容含义触发条件与经证据支持的实际暴露独立分类，任一重大则Owner路线；无法分类保持未决。专业审核可提供结构化证据，不要求建设通用NLP或让AI裁决。促销按实际商业影响分类。

Required evidence:

- 语义否定/限制/商品事实改变的短编辑不能靠字符比率降级；暴露字段篡改不得影响权威分类。
- 普通小额促销与重大促销各走正确路线，作者、专业审核与最终批准权限按原矩阵。

### S4-DR-R1-009 — 正文误用512字符元数据校验并strip，合法长文被拒绝且准确文本被改写

使用适合正文的准确值处理与平台/类别长度依据；保存原值、显示值和已接受表示等价各自语义，不能静默截断/strip。保留必要Secret/PII防护，不为支持长文取消敏感字段控制。

Required evidence:

- 按文档资格测试长文、多字节/码点、换行/首尾空白、超长拒绝与原值hash；管理回读与恢复均保持准确前值。

### S4-DR-R1-010 — 完整影响集合由“已观察且已映射”推断，映射变化未进入冻结摘要

通过既有身份/能力权威证明原生作用边界与完整性，绑定影响集合及实际消费的映射有效版本；变化仅失效真实依赖，不把局部集合当完整、不建立第二套映射权威。

Required evidence:

- 不完整分页/未证成原生范围必须明确INCOMPLETE；合法多Variant完整集合可以推进。
- 映射重绑定、成员增删、不同原生作用粒度的同类扫描，验证旧审批/保护不得误用。

### S4-DR-R1-011 — Owner校准包仅有只读表与约束，缺少受治理接受/激活路径及按用途的依赖延续

在既有Policy/Approval边界交付准确校准的受治理生命周期和完整组合验证；不授予任意写表，不自动生成生产数值。按范围/用途/实际依赖处理有效期和延续，历史结果使用冻结包。

Required evidence:

- 真实DB+HTTP以专业人员和Owner完成合成包验证/接受/激活/冲突/到期/替换；拒绝自批、缺证与越界。
- 只有受影响或无法证明的未执行批准重新处理，独立依赖经当前复核可以延续。

### S4-DR-R1-012 — 累计额度未绑定完整轴集合和准确需求，换版本可能把现存承担从余额中清零

同一累计权威维护跨配置版本的实际承担和所有必要轴；启动需求由准确动作证据确定。层级适用关系须来自接受的Policy，不靠选择一行消除仍有效约束。取得额度前不得进入外部承担。

Required evidence:

- 真实DB并发100/70/70、版本切换、未决旧占用、缺轴、请求篡改、不同批次与人工/API同余额测试。
- 预算不足保留批准但拒绝启动；合格启动不重复预占/实际占用，不引入Q085已拒绝的提前预占。

### S4-DR-R1-013 — 占用释放只验证有一条观察/旧值匹配，不能证明已经停止或不可能应用

每个轴的停止/历史义务解除/明确未应用应由用途匹配且独立合格证据证明；Unknown保持占用。所有可写DB入口绑定真实调用身份/目的及可验证事实，不能依任意数值缩减占用。

Required evidence:

- 应用角色负面测试仍运行、异步旧值、无关观察、跨动作证据、无调用证明、0改写、错误轴不得释放。
- 确已停止新增与确已解除历史义务分别释放相应占用，不能统归一种全部释放。

### S4-DR-R1-014 — 促销准确条款在启动后才自由录入，退出与存量纳管没有消费准确商业授权和完整承担

把准确条款、费用、并存背景、退出条件、完整集合及剩余承担在预览/批准/启动前共同绑定；人工记录真实事实与资格区分。存量先计实际承担，合格接管不补历史；退出仅消费适用准确授权且独立核验。

Required evidence:

- 从Console/HTTP完成两类促销的真实本地合成端到端；修改已批条款、伪原因、缺原授权、未核验停止/残留解除都不能取得相应资格。
- 已有不明活动可记录但不作安全PASS；新旧共同额度不重复、不遗漏；无需新增促销API。

### S4-DR-R1-015 — 促销模拟未表达完整有限经济条件，阶梯费用选错且保守场景标志不生效

复用统一经济口径，支持合同内有限但完整的费用/时间/并存条件，正算反算一致；费用阶梯选择按接受规则。必要保守情景及输入资格由证据/Policy验证，不接受前端布尔自证，不新增通用价格优化器。

Required evidence:

- 正算反算共享固定费/阶梯/退款影响/平台承担/未知费/币种单位，反例正确且无重复扣费。
- 不保守或未证成必要场景不能支撑准入；无可行解与无法判定区分；负数量/越界折扣拒绝。

### S4-DR-R1-016 — 人工核验未绑定顾客侧展示证据与实际操作边界，报告/事实资格仍可混用

管理、展示、人工报告分别验证自己的来源、范围、版本、时间和职责。事实接收不能被禁止以隐藏偏离，但不得升级其使用资格。促销核验使用准确活动条款，而不是Description digest的共用空目标。

Required evidence:

- 真实DB/HTTP负测跨Listing展示证据、早于动作的证据、覆盖不完整、执行者自证、过期操作和晚报；不同阶段的状态明确且不相互冒充。
- 同时证明合法人工更新可完成完整闭环，不以所有结果UNKNOWN掩盖功能缺失。

### S4-DR-R1-017 — 运行期偏离与跨域处理缺少权威证据闭环，任意核验ID可成为关闭依据

核验引用必须证明与原事件的准确关联与处理结果；合法迟报补证不重批，未获准偏离保留历史并消费真正前瞻批准，未决继续限制。跨域跟踪止于本Slice实际依赖得到重算，不接管长期专业整改。

Required evidence:

- 合法迟报/未授权变化/Unknown三分支、错事件核验、无效前瞻批准、处理报告不等于依赖恢复的HTTP+DB场景。
- 拒绝靠重导入/改名存量抹去历史；真正独立范围不因一项受阻一起等待。

### S4-DR-R1-018 — 限域隔离的依赖传播与重新启用未消费当前原因和调用权威

按合格原因和实际用途依赖落实限制及解除；有资格的技术/经营人员可独立关闭，解除保留双方独立当前责任。复用原控制权威、校验调用主体，不新增一轮Owner普遍签批或全店连坐。

Required evidence:

- 真实应用角色/HTTP验证缺proof、错actor、撤权、过期依据、单人双身份与原因仍在时不能解除。
- 局部原因只限域；共同K7消费者均受限，独立K9范围不受牵连；对应Outcome失败须联动准确隔离。

### S4-DR-R1-019 — 一次明确启动没有接通Description Command，命令结果也未闭合业务动作

一次准确启动在当前全部条件成立且取得额度后，进入唯一Command/Outbox链；采用事务/outbox等普通工程方式确保失败/重放不产生重复承担。平台接受、管理核验、展示和Outcome仍分开回流业务状态。

Required evidence:

- 完整HTTP→DB→worker→本地fake→Raw/readback→Console正向与故障链；单次启动恰好一个逻辑Command，多点/重试不重复。
- 命令完成/失败/未决在同一行动可追溯，不能仅测独立domain函数。

### S4-DR-R1-020 — 精确恢复复用原命令和原批准，COMMAND_RESOLVE被扩成新的业务恢复授权

仅恢复捕获完整前值仍需新的准确处置目标、当前复核及适用批准；技术尝试连续处理不重复签字，但不能借原改变的批准授权相反动作。利用所证明的条件写/冲突控制，未证成恢复用途保持限制。

Required evidence:

- 无新恢复批准、错批准对象、已过期原批准、后来合法版本、空前值、RESTORE超时/异步多次轮询均有真实DB+fakeHTTP负测。
- 合法精确恢复正向完成且不影响无关字段；不得通过关闭所有恢复功能“修复”。

### S4-DR-R1-021 — 请求guard没有绑定准确原生目标和正文节点，跨平台硬编码又拒绝合法Schema

由已核验平台Schema/profile验证目标身份、属性数量、准确正文、非目标字段和适用声明，不能靠子串/任意leaf数。文档未证成的WB覆盖写可以保持该路径禁用，不能因此损坏另一合格平台；不新增写字段。

Required evidence:

- 实际adapter+本地HTTP捕获wire payload：错native目标、第二目标、重复属性、正文只作子串、伪声明均在socket前拒绝。
- 至少一个合成验证profile下的合法文档形状可准确发送；能力资料仍不得标成真实VERIFIED。

### S4-DR-R1-022 — 等待头和异步结果的完整协议语义未落实，可能提前重试或误认终局

保留并验证真正相关的响应头和单位，不短于官方最早允许时间；超过批准有效期则停止而非延长权威。严格区分传输、任务受理、最终应用和读回；仅已证明的幂等/未应用可以支持再次提交。

Required evidence:

- adapter/worker/真实DB状态测试覆盖合同API-T08/T11/T15/T18；等待必须观察实际scheduler最早时间，而非只测单位helper。
- 原生任务/商品匹配依已验证Schema或查询唯一性，拒绝矛盾身份及不存在的任务关系；不虚构供应商保证。

### S4-DR-R1-023 — 5/15/60重算只有标签和健康重算，队列领取缺少成功占有证明与崩溃恢复

复用既有调度/租约权威完成原5/15/60内部责任，当前指标/保护/授权消费者准确重算和版本化；证明实际领取及围栏，崩溃可恢复且幂等。关闭默认flag是安全初态，不是本项缺失行为的替代。

Required evidence:

- 多worker、crash窗口、过期worker、同事件重放与队列无事件完整周期的真实DB测试。
- 以迟到退货/当前必要证据失效验证实际消费者在期限内更新，核对source/acquisition/processing时间不混。

### S4-DR-R1-024 — 责任时钟与风险/机会激活只有孤立helper，实际Task仍按固定期限和手动候选建立

在现有Task和Policy权威落实两阶段人工SLO、覆盖日历、有限停钟/暂缓及确定性激活排序；不增加通知渠道或新工单系统。跨域承接/回流消费同一原责任，不因转派重置时钟。

Required evidence:

- 通过运行工作流与可控Clock/Calendar验证连续风险和普通覆盖不同计时、合格hold有限停止/恢复、转派不重置、到期自动复核。
- 新证据产生一项合格Case/Task且重放不重复；弱信号仅观察，已受理责任不因UI刷新丢失。

### S4-DR-R1-025 — 必需的按需AI、原文主题纠正、日周复盘与经验复用未形成实际业务路径

复用现有Gateway和Raw/Metric/Task权威补上有限按需功能、原文关联/修订和本Slice日周输出；保留事实/推断/未知，模型不可计算正式利润、授权或自动调用。知识复用不复制效果与批准。不新建AI平台/通用报告平台。

Required evidence:

- 本地fake模型经真实业务入口验证字段白名单/证据引用/拒绝输入/人修订/输出校验/不可自执行。
- 原反馈去重与否定误分类修订、日周材料同一版本、阶段性经验适用/失效回收端到端。

### S4-DR-R1-026 — 双语桌面页面有状态和按钮，但准确审批材料与多项必达用户旅程缺少可用入口

以既有Console或必要共享页面形成完整、可下钻、权限正确的中俄桌面旅程；不必每屏复制资料、不强制新增某个页面框架。精确单项批准不能只看hash/数量。手机、邮件、专用导出仍不是本Slice新增必达。

Required evidence:

- 浏览器E2E从来源/候选到预览审核批准启动核验评价复盘，覆盖两语言与不同角色/普通重大/人工API/批次部分受阻。
- 核对真实网络和数据库结果，不仅Mock组件；无权限材料不可见，缺必要材料不可批准。

### S4-DR-R1-027 — 验收状态把局部helper/编译/表形状提升为完整LOCAL_VERIFIED，关键运行证据仍缺

同一返工中修复代码并强化测试，按criterion→执行路径→真实命令/结果/环境回写证据，诚实分开完整通过、局部与未运行；不得降阈值/删场景/只断言默认禁用变绿。外部生产义务仍按合同保留，不能要求真实业务增长补工程证明。 纠正OQ-121/124与原请求的映射，不新建重复证据登记。

Required evidence:

- 本地隔离完整后端单元/架构/权限与真实DB clean-install+从base升级、调用链与并发崩溃测试；frontend lint/typecheck/build/unit和浏览器E2E。
- 核对历史迁移不改、基线共享价格/广告/身份/Raw/审批回归；列出适用性能/恢复场景的实际证据和明确未满足项。
- 独立重放本Frozen Set全部反例；本轮Controller环境无法运行项不作为产品错误的证明，但Final closure必须取得其适用证据。
- OQ与F-M01/F-M02、F-W01/F-W02、F-S01逐项用途追踪一致，保持原受影响Gate，不扩大问诊。



## Implemented checkpoint: current authorization and measured source coverage

Outcome evaluation has its own role-and-scope permission, checked before business mutation. Simulation disclosure uses the product scope captured at computation and current store/product evidence grants. Unknown historical scope is masked. Executor identity does not preserve access after current store visibility is revoked.

The Console preserves full description Unicode text and digest, separates human-entry provenance from batch-import provenance, and passes the authenticated requester through the canonical calculation ledger for manual runs. Read auditing retains its mandatory transaction contract.

V0080 adds narrowly eligible scopes without provisioning any grants. V0081 adds immutable source-window coverage receipts and measurement input lineage. Each receipt binds exact listing, evidence path, window, retention definition, completeness watermark and input digest; official receipts also bind the exact summary and available equivalence profile. Database checks reject borrowed provenance, foreign summaries and profile scope mismatches. Historical aggregates without the new explicit retention definition do not retroactively acquire qualification. Missing source rows differ from certified empty sets. Contradictory counts remain in lineage and cannot be clamped into a usable ratio.

Measurement inputs now consume source-calendar transition exclusions and preserve the whole-window figures in lineage. Linked sales resolve as-of immutable correction chains; the chain and effective IDs are retained, and conflicting successors prevent qualification. These changes do not yet complete the frozen target-version plan, comparison, canonical protection or automatic Outcome-revision work. The final design and evidence will be synchronized with those remaining chains before handoff.

## Next implementation chain: frozen evaluation authority

The frozen plan must name each node's exact measurement window, retention stage, target-description coverage evidence, baseline/reference cohort and traffic weights. The plan records the original calibration package version; historic evaluation reads that exact package as of freeze, while current safety resolves current policy independently. Activation/retirement history must be preserved. A newer package cannot relabel an old result as a different experiment.

The existing evaluator's request `protections` and `conservativeBound` are not authoritative evidence. The replacement consumes canonical MetricValue identities for direct and linked profit, returns and supply, with exact business periods, source coverage and Operational/Settled eligibility. Missing dimensions yield UNDETERMINED. Each frozen critical group carries its own non-worsening basis and threshold. Primary improvement requires the admitted comparison method and pre-action source weights; raw actual rates remain separately visible. An absolute retained-visit ratio is not an improvement.

A formal threshold miss is separate from a futility/stop decision. A stop is possible only where a predeclared independent stop rule and maturity evidence establish it. No stop rule and no cross-period tail are valid absent/not-applicable plan states when the frozen authority does not require them. The future migration must remove the old universal positive-tail requirement without changing historical plan bytes.

Full verification for this chain must cover signed authorized and unauthorized Outcome requests; foreign/same-listing-wrong-window/wrong-stage measurements; no effects from rejection; calibration rotation without changing historic thresholds; no unsupported caller-supplied PASS; missing conservative method; fixed source composition; critical group failure/unknown; optional stop/tail; late revision continuity; and actual protection MetricValue/source identifiers.


The fixed-traffic contrast arithmetic keeps actual totals separate and refuses to renormalize missing strata. Its component Wilson interval formula is checked against [NIST's proportion interval reference](https://www.itl.nist.gov/div898/handbook/prc/section2/prc241.htm). This is a mathematical component, not evidence that a particular cohort, control group, repeated-testing schedule or confidence allocation is eligible. Formal use requires those qualifications and the critical value in the exact accepted frozen method. No default confidence value is introduced. Until the service and plan integration is complete, this helper does not close finding 004 or 005.


## Governed calibration lifecycle (implemented boundary and remaining consumers)

Extend the existing operationsworkflow Policy boundary with scoped calibration prepare, professional validation and exact Owner acceptance/activation operations. Retain core calibration tables as read-only to the application role; SECURITY DEFINER operations consume the shared one-use authenticated invocation proof, recheck the proof's actor/organization, current role/scope and MFA, and lock the package version. Draft payloads carry category values/units, scope, purpose, period, evidence, rationale, impact and differences; no default production values are synthesized. Validation binds the entire canonical draft digest. Owner acceptance binds the same digest and cannot be issued by the drafter or validating professional. Acceptance and activation are distinct events; activation rechecks unchanged validation/acceptance, exact effective range and overlap, and preserves prior activated/retired timestamps and immutable accepted values. A professional may prepare and validate; the independent Owner acceptance is the approval boundary.

Use explicit purpose/component dependencies for consumers instead of treating a new package ID as invalidation of every prior approval. Historical result authority stays the exact package/plan snapshot. A stale/expired/revoked package cannot support new execution, and a successor cannot revive canceled authority. Tests must enter through real signed HTTP and the existing isolated identity issuer, not seed acceptance with administrator SQL. Historical privileged fixtures remain clearly synthetic and do not prove the new lifecycle.


## Mapping lineage and native scope completeness

V0083 replaces the identity-only affected-set digest with an ordered snapshot of listing/native identity, observed members, the actual canonical mapping IDs/versions/effective intervals/targets, product lifecycle states and open conflict identities. UTC serialization makes the digest independent of a connection's timezone. New affected-set rows capture that exact JSON and reject a digest or member-array mismatch; old rows and original approvals are not rewritten. Existing binding checks consume the new digest, so a mapping change cannot reuse an old approval even when the platform variant key is unchanged. Scope is local to the consumed listing; an unrelated listing's mapping does not invalidate it.

This does not prove that the observed native membership universe is complete. Finding 010 remains open for that half. The acquisition/identity seam must supply source-backed completeness for the exact native action boundary, including pagination/whole-group scope and the consumed capability/normalization version. A locally nonempty list or an operator-selected subset cannot supply that qualification. No new mapping writer is introduced.

## Provider timing and scoped command controls

V0084 derives provider timing from retained response headers. Ozon `Item-Retry-After` is minutes; WB `X-Ratelimit-Retry` is seconds. Standard `Retry-After` keeps its own delta-seconds/HTTP-date semantics, per [RFC 9110 §10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3). A wait is never shortened to 3600 seconds. Multiple applicable headers take the later bound; malformed, duplicate numeric, foreign-unit or unrepresentable values become an explicit unknown hold. The current date parser accepts IMF-fixdate; other unrecognized formats remain held for resolution. Historical responses that did not retain native timing cannot be retroactively certified as having no wait.

The durable command bound survives state transitions and restart. New lease fences, calls at an existing fence, scheduler candidates and the final adapter authority check all consume it. Deferred readback preserves the observation request and releases the lease; it does not resubmit the original write. Timing does not extend the approval or change retry proof/budget. HTTP 409, like 408/429/5xx, is inconclusive for a dispatched mutation and cannot prove rejection or permission to resubmit. The remaining task/item correlation, exact response schema, per-purpose status semantics and expiry-to-resolution lifecycle still belong to finding 022.

V0085 fixes a transitive actual Gate defect exposed by multi-organization fixtures: a capability flag must match the command capability, and a scoped stop must match the command's platform/account/store. Retired flags are not current controls. Another capability cannot lend enablement; another store cannot impose a local stop on this one. Existing global controls remain global. This targeted repair does not close the broader containment/dependency finding 018.


## Frozen evaluation semantics and exact canonical periods (in progress)

V0086 permits an explicitly accepted zero cross-period tail. An explicit empty stop object differs from a missing/invalid stop category. Plan snapshots retain the complete structured method, schedule and critical-group rules, including their own bounds; the new plan digest includes these rules, comparison/transition basis, boundary and freeze time. Existing plan rows and original package values remain unchanged. First plan preparation timing, semantic method admission and actual qualified node execution still require implementation.

Java and SQL protection-vector reducers now require every necessary dimension for PASS and preserve a known FAIL despite another missing dimension. A missed lower improvement bound is not proof of futility; only a separately qualified upper improvement bound below the frozen stop threshold at its eligible node can support that inference. The current service has no qualified upper-bound pipeline and therefore emits UNDETERMINED for configured stop rules instead of claiming CONTINUE. An explicitly absent rule is NOT_CONFIGURED.

The shared canonical Metric query now supports exact business-period selection, preserving latest revisions and evidence without accepting a larger/shifted period. Existing covering-period callers keep their original semantics. This is a read projection over the sole Metric authority, not a new calculator or writer. Actual engine/database tests cover the narrower-period rejection, competing shifted window and latest unavailable exact-period revision.

Legacy Outcome request numbers no longer supply the conservative comparison bound or any protection verdict. The absolute measured ratio remains a source fact; without the qualified fixed-structure comparison and canonical protection evidence, the formal result remains UNDETERMINED. This is an interim removal of an unsafe authority path, not closure of finding 005: positive qualified Operational/Settled results, group bounds, exact frozen windows, automatic revisions and dependent action behavior remain required.


## Canonical scope projection and measured source strata (in progress)

`CanonicalScopeMetricQuery` stays inside analyticsdecision. It reads each declared member through the canonical exact-period query, validates organization/store membership before reading numbers, sums compatible profit amounts, and computes overall return rate from total return/completed units rather than averaging member ratios. Missing members, unavailable/unverified values, incompatible currencies/definitions and a zero return denominator remain explicit gaps. Original Metric values, confidence/estimate states, verification runs and provenance are retained. This projection does not prove native scope completeness or grant formal protection qualification; frozen scope/method admission must consume it.

The actual Metric Engine test publishes synthetic spend and tax inputs through the existing fact repositories, computes profit through the real writer, then removes an applicable cost and observes a new unavailable profit revision. The historical scope result remains readable at its original instant. This is not real provider evidence or a new financial authority.

Detail measurements now preserve ADVERTISING/ORGANIC/UNKNOWN visit and retained-visit counts over the same transition-filtered cohort as the primary result. Qualification remains separate from those diagnostic counts. A late sale reversal produces new source-stratum counts and preserves the original result and lineage. The exact measurement/listing reader returns no evidence for another listing; legacy lineages without source-stratum qualification remain unqualified.

V0087 adds a reproducible digest of retained PostgreSQL JSONB inputs without altering original inputs/digests. A new insert trigger requires matching source coverage organization, listing, path, complete window, retention definition and chronology. Qualified measurements cannot drop their receipt. The trigger computes the new digest, and a CHECK verifies it; PostgreSQL's stable encoding conversion is not falsely declared immutable to force a generated column. No retrospective method/source qualification is inferred from the new derived digest.

The next dependent work is a normal pre-approval frozen comparison plan, complete target-version window admission, bound method/node execution and independent canonical protection comparisons. The current formal Outcome remains UNDETERMINED until these dependencies are satisfied. Source-stratum arithmetic and scope totals alone do not close findings 004–007.


## Plan preparation, review and approval identity (in progress)

Normal action preparation now freezes its resolved evaluation rules while the action is still DRAFT. First-time plan creation after review/approval/launch is refused; launch can only reuse an existing plan. The review facts digest includes the plan digest. V0088 enforces chronology and exact action/calibration identity on plan insertion, captures the plan digest in an attested review, and requires approval to bind the same pre-existing reviewed plan and target/current/affected-set digests. Existing binding checks retain every prior condition and now reject a missing/different plan binding. Historical approvals are not backfilled with retrospective plan authority.

The action API exposes the plan digest captured in each review/binding. Evaluation reads additionally retain the complete nested frozen definition alongside the existing compact display fields. Integer maturity expressed as an admitted exact JSON string retains that representation; unsupported/fractional/missing maturity still has no fallback.

Signed HTTP tests prepare a fresh candidate/action after withdrawing the fixture's unused action through the normal cancellation route, and prove the plan exists before any review/binding. The one-live-action constraint is preserved. Synthetic review/approval fixtures now create their plan before review, not afterward. These fixtures test control invariants and do not prove full qualified business outcomes. Complete target/reference windows, method eligibility, purpose-specific plans and canonical protection evaluation still require the next work.

## Executable finite-node method and retained evaluation admission (in progress)

`EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1` is a supported arithmetic implementation, not a newly accepted business calibration. A normally governed package must explicitly name the method, its independent-Bernoulli sampling model and qualification reference, the family alpha and each node's allocation. No confidence or target default is supplied. Every node is parsed as a member of the full frozen family; duplicate nodes/groups, overspending, inconsistent confidence policies, missing parameters and unsupported methods cannot obtain method admission. The node allocation covers both tails of reference/target × advertising/organic for the primary comparison and every frozen critical-group comparison. Alpha is rounded down; interval endpoints and differences are widened/rounded outward. Counts outside the supported quantile range remain unavailable rather than narrowing or overflowing.

The exact component intervals use deterministic beta quantiles from Apache Commons Math 3.6.1 (Apache-2.0; add this dependency to final SBOM/license verification). The implementation uses no random sampling. Exact-binomial endpoints handle zero and all successes; a test independently checks actual binomial probability coverage across n=1..20 and p=0..1. Fixed weights preserve both sources and cannot renormalize a missing positive-weight source. Actual aggregate measurements remain separate from these standardized contrasts.

Statistical references: [NIST exact binomial confidence limits](https://www.itl.nist.gov/div898/software/dataplot/refman2/auxillar/exacbici.htm), [NIST Bonferroni method](https://itl.nist.gov/div898/handbook/prc/section4/prc473.htm), [Apache beta distribution and quantile accuracy](https://commons.apache.org/proper/commons-math/javadocs/api-3.6.1/org/apache/commons/math3/distribution/BetaDistribution.html). The application to the finite frozen family is this implementation's union-bound construction; these references do not certify marketplace sampling or a particular control design.

Each method node also specifies integer `windowStartOffsetDays`, `windowEndOffsetDays`, `notBeforeOffsetDays` and `lastOffsetDays` relative to the immutable plan freeze. The observation interval is fixed before review; the first permitted evaluation cannot precede its end plus the accepted 7/14/30-day retention. The plan's latest boundary includes the last actual scheduled node and the explicitly accepted tail. A missing/late launch, mismatched measurement window/retention, incomplete source maturity, future computation or premature/late first evaluation cannot qualify that node. After the deadline, a retained, previously admitted same-window result permits a correction of that exact cohort, not a new rolling window or a new experiment.

V0089 retains each new result's exact plan digest, requested node/stage and qualification gaps; historical rows remain without invented evidence. Transaction-scoped advisory locking serializes the plan's revision allocation without granting UPDATE on immutable plans. The read API exposes the retained qualification evidence. An unproved requested Operational/Settled stage produces an UNKNOWN Task outcome rather than fabricating a business-stage observation.

Limits at this checkpoint: the exact interval calculator is not yet wired to a verified control/Description-coverage projection or canonical protection decisions. Node timing admission alone does not establish these qualifications. Formal positive Outcome remains unfinished; every finding remains unclosed. One first HTTP run returned an unexplained preparation 403; subsequent targeted and full diagnostic reruns passed. Preserve the failed run and investigate this intermittent failure during the remaining complete regression; do not describe it as a corrected root cause.

## Display input custody and exact manual verification (in progress)

Measurement lineage now retains the entire relevant display-observation snapshot, including unknown/not-displayed reports, tied latest pre-window observations, source references and acquired timestamps. Reading at the original calculation time excludes observations acquired later, and a new measurement retains its own snapshot without altering the original. A display report still does not establish whole-window target-version coverage. The former unbounded, DISPLAYED-only query was removed.

V0090 adds a derived exact-observation binding to new manual verifications. The existing independence and management-digest guards remain. Additional checks bind customer display to the same listing, target body, original packet and recorded operation time, refuse evidence acquired after verification, and verify that a human observation's provenance identifies the independent observer. Executor/report-author observations cannot be laundered through another verifier; a human record cannot be labelled official. Management match and customer display remain separate answers; a management match with unknown display is allowed. Each display binding explicitly states OBSERVED_INSTANT_ONLY and is exposed by the read API. Historical unbound verifications remain readable but cannot newly transition a manual action to VERIFIED.

This addresses part of S4-DR-R1-004/016, not their full closure. Full period-coverage qualification, native source semantics, late-evidence downstream recomputation and the remaining dependency/release/Outcome paths are still required. No source profile or real provider coverage is inferred from the synthetic tests.

## Atomic API launch and its only Command (in progress)

V0091 preserves the prior launch function's authorization proof, binding, state and allowance checks. An API launch now creates its exact Description Command before the launch transaction commits; a manual launch creates none. A deferred database constraint checks the exact action/launch/binding/organization relation at commit. Failure creating the Command rolls back the action state, launch and all occupations together. The normal HTTP launch response returns the Command ID; a second click cannot create another command or provider attempt.

New launches receive the database transaction ID; historical launches remain NULL without invented transaction authority. A new Command can be created only inside that authenticated launch transaction and for its actual launching actor. The existing idempotent Command read also checks current actor scope. A V0090-to-V0091 isolated upgrade test preserves the original historical launch fields and refuses creating a missing legacy Command later. Such an old orphan needs controlled investigation/recovery; it is not silently republished.

Queueing does not enable a platform call. All original lease/pre-socket write gates and default-disabled worker configuration remain. The old Gate test that created a Command after launch is replaced by checks of the actual timing: a moved current body before launch leaves no launch/occupation/command; a move after launch refuses a lease and leaves zero provider attempts. Its initial failed run is retained as a corrected test-timing assumption, not an ignored Gate failure.

S4-DR-R1-019 remains open for exact qualified asynchronous/readback result propagation into the business action and full runtime recovery. S4-DR-R1-013 release proof depends on that terminal-execution evidence; arbitrary display/old-value evidence is still not a sufficient release design. No finding closure is claimed here.

## Frozen native response identity (in progress)

V0092 adds a nullable, technically verified per-operation response descriptor; it installs no official provider contract. New Commands derive their native listing key from the exact approved affected-set snapshot and keep it immutable. Historical Commands remain without invented native identity. Each new attempt retains that identity and, for status enquiries, the latest mutation attempt's actual accepted task key. An older accepted task cannot replace an unknown newer mutation.

Classification consumes the frozen descriptor and retained Raw bytes. The initial supported descriptor selects an exact object or an array with exactly one matching native identity, preserving typed identity values. A status reply additionally echoes the exact accepted task. A task-acceptance-only descriptor can record asynchronous receipt, but cannot certify final application or a readback. Description and marking observations require their actual JSON types. Duplicate JSON keys, absent/contradictory identity, missing required marking, unknown task/status or missing descriptor remain unknown; HTTP status alone cannot prove business rejection or non-application. Original response custody and provider waiting rules remain in force.

The supported echo-binding descriptor does not invent an echo field in a provider response. Providers whose documented response omits it still need a verified query-uniqueness binding through the exact rendered request; that path remains pending. Likewise exact management readback identifies the object and observed value, not causality of a particular unresolved write or customer display. Pre-socket request identity, whole-card preservation, business projection and combined scheduler/database/fake-HTTP recovery remain in scope. No finding closure or live platform qualification is claimed by these synthetic checks.

Further same-class controls in V0092: a task-only receipt cannot supply the explicit non-application proof; that branch requires exact object evidence. Native status is derived from the retained typed status or HTTP status, not the caller's label. A status attempt with no retained response stays UNKNOWN even if its caller proposes REJECTED. Worker-local inability to enquire (missing credential or unbound historical identity) defers the existing task without manufacturing native rejection. The final pre-socket query compares the unchanged operation snapshot separately from its derived response identity, while requiring the command's frozen key to match both request and current listing.

The existing `configure_registry_draft` and independent registry review path still have pre-listing limitations (no Description field intake and price-only write review). Extending that same controlled path is required next; fixture-written descriptors are not claimed as normal governed configuration evidence. No ad/price authority may be weakened while adding Description support.

## Controlled Description registry maintenance (in progress)

V0093 extends the existing registry functions rather than introducing a new configuration writer. Description operation drafts may carry their text/marking/attribute fields, response binding and explicit non-application response fields. These additional fields are restricted to the Description capability; existing non-Description values and price authority checks are preserved. Drafts remain unverified, versions are checked, and an existing verified configuration must first enter the existing revision workflow.

Description snapshots and independent verification use CONTENT_WRITE authentication metadata. Review still requires two current authorized people, the exact submitted configuration digest, complete endpoint membership, explicit real-account-class evidence, current validity and existing restore precondition semantics. It additionally refuses missing/malformed Description response descriptors. A new or revised descriptor must bind the submitted verification evidence reference; a renewed account attestation over an unchanged verified configuration retains that configuration's existing protocol reference and exact digest. Neither configuration nor verification creates a Command or enables a write. Test cases use a fictional account and fabricated attestation values to exercise the workflow; these are not actual provider qualification.

Exact rendered request validation, non-echo query identity, the complete UI and actual combined worker/DB/loopback-HTTP trace remain pending. No finding closure is claimed by this configuration step.

## Exact rendered Description request schema (design in progress)

Replace key-name heuristics, substring matching and the 12-leaf limit with a closed JSON request template from the same independently verified operation. Bound scalar nodes identify the exact native listing key, Description attribute key, full text and applicable marking declaration. Object key sets and array cardinalities are exact; literals are fixed protocol metadata. A request must contain exactly the required semantic bindings, with their declared scalar types. A body cannot hide the target text in an unrelated field, substitute another attribute or add a second item. Marking applicability is explicit instead of universally requiring a provider field.

Metadata describes partial-attribute or partial-field semantics; whole-card replacement without qualified preservation/concurrency evidence remains unsupported. This does not qualify WB full-card writes or infer Ozon attribute 4191. The normal controlled registry draft/review must carry and validate the descriptor, freeze it with the operation, and leave historical absent descriptors unqualified. Existing price/ad request behavior remains untouched. Runtime validation must check exact Unicode without trimming and the command's verified category bounds before a socket is opened.

The implemented template intake uses a Description-only JSON vocabulary and accepts a native root object or array with unique keys. The authenticated service permits the deeper closed shape only in OPERATION.description_request_guard; ordinary metadata and submitted evidence keep their prior depth bound. SQL independently validates semantic binding counts/types, protocol reference and supported partial-write semantics. The request is parsed with duplicate-key/trailing-token rejection, then matched exactly before destination preparation.

The Description adapter now prepares the outbound destination before resolving any credential, clears resolved character buffers and checks attempt authority again immediately before exchange. A configured conditional write must carry its exact bound version; RESTORE cannot omit the precondition. Case-insensitive collisions with authentication headers are refused. If-Match accepts one strong entity-tag; wildcard, weak and multiple tags cannot stand in for that exact version. An opaque comma inside a single entity-tag remains valid. This is a stricter exact-version application of [RFC 9110 §§8.8.3 and 13.1.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1), not a claim that HTTP generally forbids tag lists. Actual loopback tests inspect the received header and exact Unicode body and verify zero dispatch on invalid conditions or revoked final authority. These transport checks do not authorize restoration under the original business approval; S4-DR-R1-020 still needs its new exact action and current approval chain.

JSON bodies explicitly send Content-Type: application/json. This standard header participates in destination/header-name validation; authentication metadata cannot silently overwrite it. The loopback assertions inspect this media type as well as the exact body and version header.

## Next exposure and release work (unimplemented design dependencies)

The existing allowance reader still chooses a most-specific row, sums by that row's ID, and accepts amount demand from the launch request. ALLOWANCE_AXES is already a governed calibration category but is not yet consumed by launch. Required replacement: bind the exact admitted finite axis set and demand method before review; resolve all scope constraints according to the accepted policy; derive demand from the action's affected identity and canonical evidence; lock stable scope/axis identities; carry all live obligations across allowance versions. Preview and launch must consume the same projection. No allowance is acquired during preparation or ordinary approval.

The existing observe_lc_occupation application-role function can directly lower occupation without actor/evidence, and release_lc_occupation currently mistakes the existence of an observation for proof. Both remain defects. Replacement release evidence must separately qualify cessation of new exposure, historical obligations and proven non-application for the exact action and axis. Old text/readback, a submitted exit request, evaluation completion and an operator's task closure cannot certify those facts. Unknown execution keeps its burden. Exact invocation actor/purpose must bind every controlled write. These are implementation requirements within the frozen findings, not new Owner questions or completed controls.

## Non-echo task query binding (implementation in progress)

REQUEST_UNIQUE is an explicit independently verified status-response descriptor, not an inferred provider echo. The initial supported query is POST with a JSON task scalar at the declared pointer/type. Before dispatch, the adapter retains the exact body through a controlled database function. That function checks the current attempt/lease/snapshot and latest accepted mutation task, reconstructs the frozen template with a closed vocabulary, rejects differing bytes and duplicate JSON keys, and derives immutable query hashes and identity. The final authority check still occurs before HTTP exchange. A response without an echoed task can qualify only using this retained query identity plus exactly one matching native item and the verified status type. Missing/late/foreign/stale query evidence remains UNKNOWN. Historical attempts are not backfilled. This is not yet combined Worker/database/HTTP runtime evidence or provider qualification.

Normal registry review additionally probes the declared task field with two distinct values through the supported query renderer; a static wrong field, scalar-type mismatch or unsupported GET cannot be approved as REQUEST_UNIQUE. The renderer splits only the original template, so native task text containing another placeholder's spelling is retained literally. One durable query per attempt is immutable and must be recorded while its exact lease is current; it cannot be supplied retrospectively after completion or borrowed after a newer mutation.

The first actual database/adapter/localhost test now verifies query bytes were already committed when the local HTTP server received them, then classifies the received non-echo result through the real response function. A second case expires the lease after destination preparation and proves zero received HTTP, no recorded query and an unchanged pending native task. Both use fictional metadata and fabricated verification attestations; the local transport routes only the exact fictional endpoint to its own ephemeral loopback server. No real provider, secret or account is contacted. Complete Worker/restart/scheduler coupling remains pending.

## Qualified command result to action and Task

A matched management value alone does not prove which unresolved mutation completed. V0096 retains an immutable execution receipt derived from the exact command, latest mutation, qualified native completion (including its accepted async task/status where applicable) and the subsequent qualified readback. Only a proven APPLY plus matching readback may move its still-LAUNCHED API action to VERIFIED. A missing native completion remains explicitly unresolved; containment/closed actions are not reopened. This execution state does not establish customer display, effect, Operational/Settled confirmation or release any occupation.

The command transition and receipt/action projection must commit together. Receipt delivery to the existing workflow Task journal must be durable, idempotent and owned by Operations Workflow. Automated execution evidence must not impersonate a human action or satisfy acknowledgement/action clocks, and it must not be written as an Operational/Settled outcome. A separate execution event bound to the exact receipt is appropriate; queued delivery must survive a crash and continue even when there are no new recalculation events. This establishes the prerequisite for a genuinely new exact restoration action with its own current review/approval, rather than reusing the original command's business authorization.

V0096 records the immutable receipt in the terminal transition transaction and qualifies synchronous or exact-task async completion separately from management text. Operations Workflow appends an `EXECUTION_OBSERVED` event through its existing journal repository. Locked receipt selection, append and acknowledgement share one transaction; failure rolls all three back and concurrent consumers cannot duplicate delivery. Scheduler delivery occurs even with an empty recalculation queue; a delivery failure is logged without starving independent recalculation. Existing scoped command reads expose the receipt and its pending/delivered Task projection, with Chinese/Russian labels and no invented business outcome.

Convergence control (Owner steering, 2026-09-10): each code change must map to a frozen root or its demonstrated transitive impact and an observable acceptance result. A passed test is rerun only after a relevant change, new failure evidence, or the final exact-head verification. A failed test requires a diagnosed cause before a changed rerun. During active verification, source and migration inputs remain fixed. Do not add a second implementation or test abstraction when an existing owner/port/runner can establish the required behavior. Local milestones remain intermediate evidence, not final closure; prioritize completion of the required connected paths over accumulating isolated checks.

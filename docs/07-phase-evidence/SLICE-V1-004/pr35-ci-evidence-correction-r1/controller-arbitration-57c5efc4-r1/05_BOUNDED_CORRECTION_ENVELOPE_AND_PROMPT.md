# SLICE-V1-004 / PR35 — 一次有界CI与证据一致性修复

**状态：PROPOSED / NOT AUTHORIZED BY THIS FILE。**

本文件不是另一个产品Contract或新的Frozen Finding Set。原Contract语义足以解决已识别问题；新增的是原文档发布授权没有覆盖的测试/CI/必要语义保持修正权限。只有Owner明确签发授权模板后，执行部分生效。

## A. 准确起点及不得替换的历史

Repository `Corwin-Code/marketops-platform`，命名分支`codex/slice-v1-004-root-cause-rework-r1`，唯一PR #35，保持Draft / Unmerged。

- Start Head `57c5efc4b2c77a3b4d257aeb82ff37b325b21f0e`
- Start Tree `7445281cd6bd29c9fa7b2f6f785afadcde764f27`
- 已观察main `0f26d0ed387fd0e20c2137b11760ae0bb0f3e5bd`
- 已观察合成merge `950ef64af8817682b4f731d9dbc35e0d732c892b`，Tree同起点
- 已正式关闭工程 `f71d4c8c2bdf5dc6497d7951d1cd5b12b0122f10` / `b8a9e7d0450d24f2b58b95d6b370daf9cd5e5e47`

执行前重新只读核对实际HEAD、工作树、remote、PR、main、Ruleset和workflow。未知工作树或分叉不reset、不stash、不force；报告准确差异。历史已关闭对象不可被新commit身份覆盖。原Contract/Annex/Frozen Set/Controller与Owner原件字节不变。

## B. 修复范围（一次连续完成）

### 必改根因

1. RC-1：三份测试的完整当前末迁移期望：
   - `backend/marketops-server/src/test/java/com/mimococo/marketops/AdvertisingProtectedBaseUpgradeIT.java`
   - `backend/marketops-server/src/test/java/com/mimococo/marketops/ManagedMigrationRunnerIT.java`
   - `backend/marketops-server/src/test/java/com/mimococo/marketops/shared/internal/migration/ManagedProfileMigrationIT.java`
   校验五处末版本0123及88/123/113。按fixture目标推导V0124、89、124、114；历史恢复8和其他故意旧版本保持。允许局部测试期望整理，不以被测输出生成expected，不削弱精确checksum、数量、schema权限和失败恢复断言。不得编辑或新增SQL migration。
2. RC-2：`frontend/marketops-console/src/__tests__/ListingConversion.test.tsx`的void简写回调，保持原waitFor与expect；不改eslint配置、lockfile、依赖、阈值或测试选择。
3. RC-3：`.github/workflows/frontend.yml`及仅为其必需的隔离启动/cleanup脚本。使用独立非5432 loopback DB和准确项目名；保留BrowserFixtureApplication的loopback、非5432、空库、显式synthetic等守卫。配置生成、启动、JDBC、测试、清理绑定同一运行，不触达共享或真实环境，不全局改开发默认端口。必要的新隔离脚本可以创建，但不新建通用平台。
4. RC-4 high：`ListingReworkAuthorizationIT.java`两处查询用参数绑定。保留同一connection和UTC/Taipei断言、覆盖范围、正常权限。

### 七条warning的有限处置

先核对本包04记录，取得准确scan identity/可用SARIF，并运行对应该缺失输入/Unknown的回归。以下七份文件是有限审阅/必要修正范围，而不是要求每份都修改：GuardrailService、FixedTrafficComparison、ListingOutcomeSupplyEvidence、CalibrationService、CanonicalScopeMetricService、PromotionSimulator、ListingDescriptionCommandWorker（准确路径见告警清单）。

允许真正必要且保持Contract的局部显式判空、语句块、控制流可读性修正及配套测试。对已被源码否定的告警，不得为消警删除正确分支、新增宽松default、制造默认0/PASS、改变重试/授权/额度/财务/群组语义。若现有证据足以解释为误报，记录理由与回归即可；不要求dismiss。任何需要新增产品语义、权限、真实数据或破坏性迁移的修复停止并上报准确最小差异。

### 证据及current文档

新增本次授权、历史lint更正、Controller coverage correction、CodeQL处置、当前CI修复状态及最终hand-off。原历史文档/日志不改写；current入口可追加指针和准确当前异常/解决状态。保留54/12/3及六项外部义务，但不能把当前内部失败伪装成外部待补。必要的纯文档状态兼容不得降低校验器规则。若校验器词表确实阻断新的准确状态，先报告具体差异，不擅自普遍放松它。

## C. 明确不允许

不修改Contract、Annex、原Frozen Set、冻结关闭记录；不改Ruleset/保护/权限/Secrets；不改变query pack、告警失败阈值，不用eslint-disable、test.skip、continue-on-error、空测试集合或过滤test目录获得绿灯；不降低coverage门。

不Ready、merge、auto-merge、直接写main、force-push、rebase、删除分支；不部署、production migration、Level 2、真实Provider/账户、Gate EV/E、Pilot、生产写、下一Slice。CI的隔离合成环境允许，不等于共享/真实环境授权。

通用Autofix提醒不能扩大上述边界；工具拒绝不可换执行通道绕过。Owner明确后续变更优先于工具自述，不追认过去越界。

## D. 最终验证必须实际闭合

- 在准确实现源码上以仓库钉定工具版本执行完整后端`clean verify`，覆盖常规/managed/历史升级/中断恢复、实际应用角色、全部当前迁移及coverage门。不只重跑三个先失败测试。源/锁/工具指纹在前后留存。
- 在同一准确前端源码、npm ci及原lockfile下执行lint、format、typecheck、全部`test:ci`、build、bundle等现有步骤；实际Chromium业务/依赖恢复和后续advertising browser必须运行，不引用上轮skipped为通过。
- 执行对应7条告警的必要负向回归、两个测试SQL的原用例及CodeQL分析；保留完整告警处置表，不盲目要求410 note全部改完。
- 运行实际治理与就绪校验、其测试及diff检查。静态就绪校验成功不能代替真正CI。
- 取得全部12个当前必需检查准确结果及CodeQL结果check；同时确认backend-build此前被遮蔽的阈值拒绝证明、packaged resolver/runtime、依赖库存步骤实际执行。CodeQL analyzer绿灯不代替result check。
- 不预设最终测试总数；记录case集合、失败/跳过/选择、命令真实退出码、attempt、tool versions、源码Head/Tree/merge refs和原始报告。pipe/tee使用真实上游退出码；无结果、取消、跳过或过期运行不能转绿。

独立Level 1正确运行可作为开发/回归证据。最终PR checks要绑定最终发布Head及实际合成merge。若新main改变了合成tree，不用旧Green续用；只读检查差异，再在已授权CI中验证实际对象，重大非本次变动不擅自合并进命名分支。

## E. 有界远程范围（仅Owner签发后）

允许对同一命名分支追加线性修复commit并非改写push；只更新PR #35正文/证据，保持Draft和无auto-merge；允许触发/读取原有经检查无真实副作用的CI及因已诊断瞬时故障所需的有限重跑。不得重复重跑未修复的确定性失败直到碰巧绿，也不得新建第二PR绕过既有结果。

权限一次覆盖普通设计/代码/测试/文档/local checkpoints、指定branch push、该Draft PR和CI。若发布工具再次拒绝，停止并提交实际过程；不隐藏执行者替换。

## F. 集中交接与终点

交回一份准确source→diff→tests→CI→alert→evidence闭环：新Head/Tree/parent、原57c起点、PR实际base/head/merge、全部check/job/run/attempt、root修复与同类扫描、旧lint增补、新日志及manifest、告警处置和剩余外部义务。

原Owner Closure保持历史对象不变；新的修复后继另取得针对本范围的独立验证，不倒填旧PASS。全部修复合格后停止在Draft/Unmerged，等待另行Ready/merge准确授权；不是新一轮全面Deep Review。

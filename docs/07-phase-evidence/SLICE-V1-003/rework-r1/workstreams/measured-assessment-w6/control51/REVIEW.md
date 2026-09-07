# Control engineering assessment with W6 evidence

51 AC and 7 findings have individual implementation arguments joined to exact W6 source and named XML case identities. This is a Codex local engineering assessment, not a Controller verdict. W7 final backend CI and artifact provenance remain pending.

W6 actually executed 2472 cases (1552 unit + 920 integration) with no failures/errors/skips. The historical Shape suite declaration mismatch remains recorded; the observed testcase nodes and Maven summaries agree.

F009 correction: each of 17 parameters starts from a legal 900-second approval recorded 885 seconds earlier and waits approximately 15 seconds on the real DB clock. The immutable Frozen text still says “Time-travel”; our implementation description no longer mislabels the execution.

All 24 S3-REL entries remain production-blocking and contain no newly obtained real evidence. Earlier R15/R20/R21 failures, R22 repair and R23 evidence remain source-specific history. W6 is not restamped as W7; the exact backend input-delta receipt supplies applicability only.

| AC | Individual engineering reason |
|---|---|
| S3-AC-107 | 最终封印计算最早的适用 Owner Lease/Recommendation/Policy/各 Purpose Freshness/Credential/Gate/IAM 权限边界，command 只能继承同一 expires_at。等待与幂等提交不重算为较晚期限。 |
| S3-AC-108 | 每个活体依赖的撤销/替换均追加不可逆 invalidation，恢复原状态只消除当前局部故障，不使旧 approval 重新合法。 |
| S3-AC-111 | SECDEF creator 从精确封印恢复 recommendation/candidate/approval，重复同逻辑 action 返回相同 command；Provider attempts 都挂同一 command，不能生成替代逻辑动作。 |
| S3-AC-112 | 候选、对象、受影响 digest、target、Bundle、批准和 idempotency key 在封印及 command 身份检查中关联；app 可写 recommendation 并不使其能把旧封印搬到其他候选/对象。 |
| S3-AC-113 | 合法 Retry 再次打开原 command 的 APPLY attempt，冻结 request identity、target/ prior、object、approved Bundle 与 Provider idempotency key 不变。 |
| S3-AC-114 | 由实际 registry shape 冻结 VERIFIED_NATIVE_KEY，状态必须从 pending 收敛且后续最新 native readback 是 captured prior，之后才可证明有界 Retry。 |
| S3-AC-115 | 没有 verified native idempotency 时 default once 提交由 open-attempt 自身 enforce；仅显式 NOT_APPLIED 可走同 command 例外。 |
| S3-AC-116 | Timeout 表达不可知，不表达未应用。重复 prior 值观测只能证明该时刻配置，不能独立证明第一次请求从未落地。 |
| S3-AC-117 | 无原生 key 的例外必须由 frozen verified operation pointer 对 exact response bytes 分类为 provider_explicit_not_applied，再由当前 fence 最新精确 prior readback 及全部 current gate 决定。 |
| S3-AC-118 | 完整 affected-set reservation 和 creator same-object 禁入阻断未完成 action 旁路；新对象 ID 不能规避共享 variant。 |
| S3-AC-119 | APPLY accepted 只会进入 task/status 或 Readback obligation，直到 exact配置确认才是配置 success。 |
| S3-AC-120 | DB 从 raw observation 实际 bid/currency/unit 与 captured approved target比较，无容差；native pending 仍须先 convergence。 |
| S3-AC-121 | 实际 response classifier 的等值比较拒绝错单位/币种/第三值；Worker 在第三值进入 investigation 而不是 overwrite。 |
| S3-AC-122 | 初始普通同对象二次动作没有开放政策路径，唯一独立闭环是原 lineage exact Compensation，不能借 replacement recommendation 绕过。 |
| S3-AC-123 | 补偿 authorization 捕获 original command.prior_bid，继续使用其 command 和 reservation；RESTORE 目标为30的固定 prior，不来自 caller target。 |
| S3-AC-124 | 匹配历史读回、action-bound stop、当前 target 所有权、exact compensation Gate和新 Preview→Ops→Owner 满足后，实际 lease/open RESTORE才成功。 |
| S3-AC-125 | 无新独立人类补偿批准不调用 RESTORE；第三方或未解析当前状态只 Readback/Manual Resolution，Kill没有自动反向动作。 |
| S3-AC-126 | 补偿状态机记录 COMPENSATED/failed配置事实，Outcome baseline/observation仍独立负责早期安全与经济结果。 |
| S3-AC-127 | 仅已sealed controlled或已确认开始的Manual动作预留完整 affected set；未执行 Recommendation/Packet 不占active，所有同 variant 共享边界。 |
| S3-AC-128 | reservation release 必须 actual configuration+eligible early company/critical safe观察、无unknown/mismatch/regression，旧obs不能覆盖后继纠正。 |
| S3-AC-129 | 非相交集合不被对象级互斥误封锁，仍通过组织串行aggregate admission在容量内进入，超限同事务rollback。 |
| S3-AC-130 | Canonical Priority band/tier使Protection/Regression优先于优化；overlap reservation选择也按lane稳定排序。先已执行动作仍必须保持事实观测，优先级不是自动撤销已发送Provider动作。 |
| S3-AC-131 | 同一隔离 snapshot 读取既有 Shared PriceCommand 与 exact frozen listing/variant 的价格、币种、promotion、sellability、availability 状态。已知重叠干预/已知重要变化贯穿 preview、seal、API/manual reservation、creator和最后 APPLY；上下文未知保留 UNRESOLVED，不捏造已知变化。 |
| S3-AC-132 | 同一snapshot暴露六独立轴及valueState/limit/headroom；company与每affected listing用Owner窗口canonical覆盖，缺失保留UNKNOWN，whole official crossing-left报告完整保守金额不分摊。 |
| S3-AC-133 | 每轴单独失败，没有低spend抵消active数量、低share抵消cumulative、反方向bid互抵；投影与失败列表使用同一权威计算。 |
| S3-AC-134 | 未知API和started/unverifiedManual同时占active及unresolved轴，独立配置proof仅移出unresolved，reservation须等early safety。 |
| S3-AC-135 | 普通准入的usable active上限扣除专属恢复headroom，补偿仍受整体count，而其它低暴露不能借用恢复slot。 |
| S3-AC-136 | reservation admission与最终open APPLY均复查同aggregate函数；authority expiry固定，排队和重复create不延长。 |
| S3-AC-137 | 缺失/取消/无完整覆盖的Envelope不可产生KNOWN完整capacity，新准入或发送均保持拒绝；恢复policy不复活旧seal。 |
| S3-AC-138 | 真实company/critical Early或late修正到REGRESSED通过observation锚点激活对象+集合隔离并永久invalidate重叠未执行assets，保留历史revision。 |
| S3-AC-139 | 独立 authority-version stop endpoint 只接受 Org-wide Owner/Ops POLICY_MANAGE，审定负责人须 Org-wide Ops。引用必须实际被 caller Org 的 Bundle/Manual 消费；Gate object allowlist、sealed candidate与manual profile快照将版本消费精确关联对象，避免 any Store Bundle误停全Store。Org恢复要求仍有效的Org attesters/独立Owner，所有ACTIVE消费者已更换坏版本；关闭review也不复活坏ref或旧assets。 |
| S3-AC-140 | 局部entity/affected-set containment按组织和相交成员传播，不把business failure自动提升为全Account/其他capability停止。 |
| S3-AC-141 | 最终attempt打开前持组织reservation锁并调用liveGate，已持lease不能跨过后来激活的authoritative stop；DB refusal先于外部sideeffect。 |
| S3-AC-142 | unsent pending/leased缺权限时停止新APPLY，已知或未知已发送状态仍可STATUS/READBACK收敛，无权限重新写。 |
| S3-AC-143 | Kill对issued未启动Manual撤销；started改UNCERTAIN并保留reservation，不抹已发生事实，只可后续独立配置读回与reconciliation。 |
| S3-AC-144 | Operator限Entity/affected Hold，Ops限businessStore，TECH按exactAccount/Store grant及technicalcause停止；deterministicregression从observedfact触发。 |
| S3-AC-145 | 紧急stop由当前授权人即时写入canonical审计metadata，不等Owner；reason/evidence/scope/actor/time/reviewOwner是必需行身份。 |
| S3-AC-146 | 新reenable必须独立Ops背书和Ownerfinal，stopper无单方解除通道，app也不能直接更新状态/attestation。 |
| S3-AC-147 | 五项闭合attestation经真实一次性proof写入，由独立Opsendorse+distinctOwner批准newBundle后恢复新scope；缺任一必须保持review/hold。 |
| S3-AC-148 | technicalcause比business增加独立TECH_DATA scoped closure attestation；Owner和Ops签齐仍不能替代此证明。 |
| S3-AC-149 | reenable只发newBundle/newscope，旧authorizationinvalidation不可删除，历史command和批准不能被rebind或延续。 |
| S3-AC-150 | quarantine只关闭普通侧effects，单独compensationGate允许持新批准恢复capturedprior；regression自身不构造或批准反向命令。 |
| S3-AC-171 | currentexactGate绑定平台/店铺/对象/direction/basis/value/window/exposure并封入approval；任一旧授权字段被改即使更宽也不可借用。 |
| S3-AC-172 | GateE消费Release/Shadow/adoption/EVexecution/earlysafety/coverage/exposurereferences，reference仅是外部Owner证据的结构化引用，工程不能制造real接受事实。 |
| S3-AC-173 | Gate E immutable publication核同org/platform/account/store/direction/basis、已demonstrated对象，exact native current/target/unit/currency和最大变化不超过前驱。两个Bundle的Envelope scope/currency/3种measurement窗口必须一致，六轴只能更紧（恢复headroom不能减少）。E未来Owner窗口独立，不把EV一次命令总额误当Pilot aggregate永久限额。 |
| S3-AC-175 | 候选/批准/command只能消费单一activecompleteBundle，wholecombinationvalidation和immutableversion避免多版本拼接。 |
| S3-AC-176 | Domain新增版本本身不改existingBundle的精确ref；任何已引用版本改变使oldseal失效，需要新Bundle审定而非silentrebase。 |
| S3-AC-177 | fullBundle校验实指stage/purpose/provider/target/materiality/lease/exposure/currentref，Owneractivation还比较independentendorse完整快照。 |
| S3-AC-178 | 新Bundle通过distinctpublisher→Opsendorser→Ownerfinal，不允许publisher或endorser替代Owner；Provider/credentialchange要求technicalclosure。 |
| S3-AC-179 | activation事务内supersede旧Bundle+activate新Bundle并appendinvalidations；alreadytransmittedcommand保留原identity用于status/readback，historicaloutcome沿frozenbaseline。 |
| S3-AC-180 | rollback是newimmutableBundleversion，缺失或冲突保持failclosed，不把撤销资产升级或重新绑定；deferredref仅支持原子构图，不允许commit孤儿。 |

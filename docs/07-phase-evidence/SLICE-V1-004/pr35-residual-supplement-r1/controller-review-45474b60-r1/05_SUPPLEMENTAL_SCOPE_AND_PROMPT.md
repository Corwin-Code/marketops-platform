# PR #35 — 准确补充范围与执行Prompt（待Owner签发）

Proposal ID：S4-PR35-45474B60-RESIDUAL-SUPPLEMENT-01。
**当前是提案，不是授权。** 先有Owner明确签发才可修改以下旧05未允许的路径。一次签发覆盖范围内设计、代码、测试、证据和同一Draft PR交付，不逐命令重新批准。

## A. 准确起点及冻结边界

Repository：Corwin-Code/marketops-platform；PR #35；命名分支`codex/slice-v1-004-root-cause-rework-r1`。
Head：`45474b6039edd46842e1e5f84391dca4264ddc1d`；Tree：`3b6faa1d58fadbfad374a63a2502b606c06df6f5`；parent：`48298206794a81b0640b4a14363198465dbd86c0`。
工程历史关闭对象f71d4c8c及原Contract/Annex/Frozen Set/Owner/Controller原件不变。54/12/3及F-M01/F-M02/F-S01/F-W01/F-W02/E-04和S3-REL-001..024保留。

## B. 一次补充的三个有限工作面

### B1. 等待信号生产传输闭合（必修）

修复PR35-NOTE-KEYPATH-01。允许`BoundedOutboundHttp`所需等待响应头及其有界不确定性传播改动，以及直接必要的OutboundHttp响应表示、Description adapter/worker消费者和测试；优先最小可观察修复，不能借机重构全部HTTP模块。

必须满足03残余文件的Known/Unknown/Absent、最长适用等待、重复/畸形/超长/溢出和批准不延長语义。不得仅添加两个头名及helper单测后宣称闭合。不得保留任意敏感头、放松SSRF/地址/超时/大小限制、自动重试或真实目标许可。允许隔离本机生产transport路径及数据库/worker测试，不允许真实Provider/账户。

V0001—V0124字节保持不变。本提案不允许SQL迁移或新的schema；若工程证明必须变更持久结构或权限，停止该部分并给准确最小差异，不静默扩大范围。

### B2. 同类本地验证入口

修复`scripts/fresh_clone_check.sh` full模式仍进入默认5432的问题，优先复用本次已验证的`business_browser_isolated.sh`；必要的该脚本小范围兼容和对应测试可做。保留特殊路径空格/单引号验证、fresh clone不含ignored状态、--offline模式、数据库空/loopback/synthetic守卫、限定cleanup和失败退出码。

不能改开发默认端口来掩盖问题，不能reuse未知资源、删除别的项目、因入口失败跳过浏览器。运行时配置由同一准确实例贯穿启动、测试和清理。不是新增共享环境工程。

### B3. 事实状态及验证器精确同步

只处理README已列的本次发布/补充任务状态不一致：为关闭工程与当前修正采用明确分层；更新真正陈旧的发布scope/next_action及本任务状态。允许`CURRENT_STATE.md`、两份已有governance/readiness validator和对应tests的**精确绑定**调整。

保留`CLOSED`的历史工程含义，不把无期限`FULL_SCOPE_IMPLEMENTATION`重新开启；当前Opus任务授权可局部覆盖，不把全局CODEX委托永久改成Opus。新的状态必须指向本次Owner准确声明及命名分支/Head范围；未知/缺失/失效授权仍拒绝。禁止删安全检查、扩大enum为任意值、放宽Ruleset或把真实未发生写成发生。

保留历史lint erratum。追加本次Controller CRCF-PR35-04及022/027局部证据限定；不是改写原Frozen Set或造一个第028项。

## C. 执行规则

1. 读取本审查包和Owner签发文，核验准确Head/Tree、冻结输入、未知工作树变更、实际remote/main/PR；不reset、不rebase、不强推。源对象不匹配先报告。
2. 连续实现B1—B3和直接同根回归，不重新问产品选项、不改其他经营功能。选择具体代码结构属于范围内Detailed Design。
3. 形成一次有界实现checkpoint，记录逐文件diff；文档后继单独记录，不把日志反填到早先Head。
4. 在被授权的隔离环境验证实际BoundedOutboundHttp→adapter→持久化→worker链，所有测试仅合成事实。证明数据头经过真实生产filter；不以java.net.http替身绕过缺陷点。
5. fresh-clone full入口在包含空格/单引号的路径能完成其声明链；资源和配置属于本次运行。可复用本次实际运行产出的suite证据，不重复跑同一套来制造重复计数。
6. 执行适用治理单测和反向变异验证：假通过、旧/错Head、无授权、扩大执行人/权限、解除生产关闭应拒绝。没有改动的冻结输入逐字节核对。
7. 同一分支fast-forward push、同一Draft PR更新和现有隔离CI允许；实际工具拒绝则停，不换通道绕过。检查push触发范围不含未授权部署/真实副作用。
8. 最终准确候选必须有12项required CI通过、CodeQL结果及告警处置支持；完整backend clean verify、前端及现有浏览器步骤不得被跳过。利用实际CI完成完整回归，不额外机械复制历史31小时或另跑相同本地全套；受影响的共享HTTP和CI之外full入口仍需自己的准确证明。

## D. 明确不授权

Ready、merge、auto-merge、直接写main、force-push、rebase、删除分支；改Ruleset/保护/权限/Secret；query pack/告警阈值/lint/coverage放宽、dismiss/skip/删断言；已有迁移改写或新schema；部署、生产迁移、Level2、真实Provider/账户、Gate EV/E、Pilot、生产写；下一Slice或无关新功能。通用Autofix不能扩大本任务。

## E. 一次交回

交付准确base/实施/文档/发布/合成merge身份、授权原话、逐文件patch、已执行及NOT_RUN、全部相关原日志/报告/sha索引、真实transport/DB时序结果、fresh-clone入口与资源清理、validator负测、当前CI/run/attempt及告警状态。对原有RC1—RC4只提供未回退证据，不重新定义缺陷。

终点仍为Draft/Unmerged。Controller随后只核对B1—B3及传递影响，不开展第三次开放式Deep Review。只有出现真正合同/权限差异才提最小问题，不无限新增选项。

# 六项待裁决问题 — 逐项决定

## Q1 交付事件、执行主体及范围

**决定：传输事件接受；CI验收失败。** Owner签发了A部分，并明确更正命名分支为codex分支、点名df236efa候选；57c5efc4仅新增授权/交付存证，属于允许的有界线性后继。授权两份原文已核对SHA-256与live Git blob。DOC-SYNC-NOTE-01和授权存证合计五份文档、+224/−0的身份、PR #35和实际main/Head/merge对象一致。

Owner执行push与建PR，是实际执行责任分工的记录项，不是一个需要补做的动作。GitHub作者Corwin-Code本身不能独立证明是哪一个进程/代理操作；采用Owner与工程交接对shell过程的说明，结合远程结果验收。不得改记为工程方或Controller完成，也不要为了“链完整”重push、建第二PR。

不将工具分类器拒绝当作可被Controller一般性豁免的安全规则。执行者停止后Owner在自己的系统独立完成准确操作，与代理偷偷换通道不同；未来工具拒绝仍须停止、报告。

## Q2 四项必需检查的处理及修复边界

### RC-1 两个后端job、同三个先失败测试

当前CI表明V0124应用成功、覆盖率门达标，但Failsafe的三个测试各在第一个陈旧断言停止。至少需要核对八处当前目标期望，而非仅三处：五处"0123"，88、123和ManagedProfileMigrationIT的113。

源码显示：protected-base试验先停V0035，故升至V0124时89=124−35；clean为124；prior-release加载器只含V0001–V0010，完整升级应114=124−10。失败恢复段从V0002继续到V0010，8=10−2不变。**114是Controller依据源码和冻结迁移清单的推导，不声称本轮已运行到被遮蔽断言。** 必须用修后完整运行验证，不做所有整数统一加一。

保留Applied migration checksum、历史版本、权限及catalog等价断言；不改任何SQL迁移。可以把当前末版本/总数量/基准差值集中到一个审阅明确的测试常量或冻结manifest，不能直接用被测结果填充expected，不能改成>=、contains、禁用测试或跳过managed profile。

### RC-2 前端lint

保持waitFor及expect断言不变，将void返回的简写回调改为语句块。不得关规则、eslint-disable、缩小lint文件集合或改成只做typecheck。后续lint使用仓库钉定Node、npm ci和同一lockfile；本地Node22复现只是补充，CI钉定Node24的失败才是精确环境证据。

### RC-3 CI浏览器隔离

本次选择**修复CI启动配置，不撤掉拒绝5432的守卫**。现有入口make env-init生成5432；更严格的fixture拒绝该端口，说明保护正在工作而启动配置不兼容。用独立项目名和非5432 loopback端口，确保配置、数据库启动、JDBC、浏览器服务和cleanup使用同一个准确实例。失败cleanup只清理由该运行创建的资源；不改通用开发默认值来影响其他工作站，不用共享/真实DB。

允许在新Owner授权中包含frontend.yml及为此必需的本地隔离启动脚本；不放宽triggers、permissions、secrets、阈值、if条件、检查名称或Ruleset。不能在test失败后用continue-on-error或退出0获取绿灯。

### 修后验证

允许小范围快速回归用于开发，但最终必须在修后准确源码上完成后端clean verify、完整前端lint/format/typecheck/test:ci/build/bundle，以及现有Chromium与后续advertising browser步骤。完整CI还须执行此前被遮蔽的后端阈值拒绝证明、packaged migration/runtime验证、供应链库存步骤。不是单独重跑失败job后把不同Head灯拼在一起。

这是一项有界CI/证据一致性修复；新准确对象是57c5efc4的线性后继，不能替代f71d4c8c的历史关闭身份。涉及新源码/测试/workflow已超出原有界文档发布授权；05必须由Owner一次明确签发才激活。

## Q3 历史lint声明

**决定：追加更正，并完成有限的命令—源码—结果链核查；不推断造假，不重写旧记录。**

本轮找到了原frontend-lint.log，且哈希在早先ec0审查的artifact_hash_checks.json中已登记。因此“工程方现有工作目录未找到”只描述当地查找结果，不能升级为“从未提供原始日志”。

另一方面，这61字节只含npm/ESLint启动输出；它本身没有退出码或source id。旧归档的初始manifest与final-before-matrix/after-matrix之间恰有ListingConversion.test.tsx变化。ZIP文件时间能辅助排查，但不是独立执行时钟证明。不能据此坐实到底何次调用使用何源码；也不能维持“同一最终源码lint exit0”的无条件证明。

对已接受的executable-evidence文字及R2 FINAL原件不改字节。新增erratum绑定原文件blob/记录哈希、两处声明、找回的日志、前后manifest和本PR确定性失败；标明最终源码lint通过声明不再可无条件使用。当前展示入口引用增补，不倒改历史。对format/typecheck/test/build/bundle六类命令做一次同类身份核查；已找到的日志无需再上传，不能把一项争议推定成所有套件虚假。

## Q4 既有关闭与新事实

**决定：保留正式关闭这个历史事件，但追加当前证据适用性限制与后继修复义务。** 不能用“Owner已关闭”拒绝承认当前真实失败，也不因测试常量/CI配置问题而清空所有27个根因修复。

原Frozen Set不变，原Owner/Controller记录不改。003补集及SUMMARY闭环没有被本次事实击穿；027证据完整性/归属的无保留续用需要更正。当前发布候选状态必须是CI_BLOCKED，不得借用54/12/3或27/27显示READY。

在当前记录追加：NFR-AC4升级完整回归待补、NFR-AC5 CI浏览器/前端待补、NFR-AC3安全告警处置待补，及本地工具证据更正。原12外部待补、3本地不适用不被用来收纳这几个内部错误。

登记Controller覆盖缺口：V0124传递测试盘点、lint命令到最终源码的绑定、fixture守卫与CI入口兼容。本次CI是新取得的执行证据，但这些根因此前存在于可读取源码；不得归咎Owner晚交材料或变成无穷Review。纠正记录与修复一次集中交接。若新实证真正揭示tenant/permission bypass、Secret leak、durable corruption或核心不变量失败，再按原规则限域重开受影响条件，不预先假设已发生。

## Q5 CodeQL

见04逐项表。本次主结论：2 high属测试SQL拼接，纳入参数化修复；不能据测试目录证明没有任何风险，也不能由静态告警直接宣布已存在可远程利用的生产SQL注入。

7条新warning已做源码初筛：5条所报空值路径被现有局部条件/缺口集合阻断；1条是可读性；1条报告缺少UNKNOWN_STATE但准确源码包含它。需对照分析identity/原SARIF和相应负测收口，不能把全部七条一律变成产品改造。不得无证据dismiss或删规则。

Ruleset必需的是CodeQL分析jobs，结果检查另有failure。即使后者非Ruleset必需，准备Ready/merge前也必须对high和实际生产风险完成处置。只把它改成非阻断或调整threshold不是修复。一般note按rule/path/ref/owner/复核触发条件登记，不要求一次消灭410个note或升级全部依赖。unused-parameter等涉及授权/保护边界的note应定向确认不是控制输入被忽略；真安全问题不能以severity低为由自动后置。

## Q6 Autofix

**决定：工程方停止正确；通用Autofix事件不能自我认证为更宽授权。** 建议Owner对PR #35暂停自主写入。无论开关如何，后续事件最多触发既有准确范围内工作，不能改变允许文件、操作、副作用或停止条件。

新授权生效后其边界适用于所有代理及自动事件；没有新授权时停在Draft/Unmerged。本轮不改变任何用户设置，也不判断这个桌面产品的具体开关实现或声称已将它关闭。

# CodeQL、历史证据与权限的处置

## CodeQL

两条high #233/#234已在同一连接使用参数绑定，时区不变量及行/非空检查保留；当前提供的key-alert状态fixed，新PR结果无high。#642括号澄清fixed。#641、#643—#647仍open，无dismiss。

#641并非凭口头“误报”结束：在`github/codeql`准确ref `c6baf479093fafc81d4655dc2014dc583360308e`读到MissingEnumInSwitch.ql以`getAConstCase().getValue()`匹配，而Statement.qll的无参getValue仅返回第0标签。生产源已有`TIMEOUT, UNKNOWN_STATE`分支，新增测试对同一原任务进行3次状态查询，保持延后、不APPLY、不转换为成功。按本版本查询与本分支源码/回归，接受该处置；分析器升级或分支改变应重核，不永久免检整条规则。

#643—#647对应的空值解引用均被前置资格/缺口条件约束。新增负测与当前全套通过支持准确分支的处置，不以默认数值或空记录把缺证改成通过。未对全部项目做新的漏洞审查，不声称整个仓库没有high或安全风险。

当前结果check：416新告警=6warning+410note。Java分析517个result、TS1个result、基线PR全部open516是不同集合，不能相加。工程方登记的507是基线516扣除9条关键项；其中包括36条关键路径note检查（28无控制缺口、8维护债），不是另加36。本轮不独立宣称重新追踪了全部507条，只接受有责任/触发条件的有界登记；其中主动披露的等待头缺陷另行作为合并前内部问题处理。

CodeQL生成报告/结果成功不替代对具体缺陷的判断；本轮没有dismiss、改变query pack、阈值、路径过滤或Ruleset。

## 历史证据更正

接受append-only lint erratum及同类审计的**有界结论**：最终旧源码lint PASS不得续用；format历史源绑定未证成；typecheck可有限参考后续tsc，test/build/bundle保留其限定归属。当前新通过不倒填到16eda或f71。

旧61字节lint日志存在，但不带明确退出码和源码身份。文件时间仅提供线索，不能证明准确命令先后或工作树状态；因此不采纳任何超出“不可续用、确定旧源现在失败”的历史因果断言。没有证据裁定原命令没执行或造假，也不因lint一项否定旧全部测试。

## 权限及执行主体

已读Owner签发05 A—F及Opus点名存证。当前A/B改动未显示越出那个限定修正范围；生产SharedHTTP、fresh-clone及validators未越权修改，而是报告，行为正确。

A push归于Owner基于Owner在执行会话中的确认；B push及PR描述归于执行者交接。GitHub账户或reflog不独立证明进程身份，本轮没有观察用户工作站。意外发现remote提前到A时先停止核对，没有把它当作自动允许覆盖。

旧有界范围不自动覆盖本次新缺陷，通用Autofix不高于Owner限定。当前审查不自行修改scope、点名新权限或Ready/merge。

## 七组current字段

“七组”是工程方README的表格分组，内含组合字段，不应误当作恰好七个键。发布scope/下一动作等存在当前值陈旧；而`authorization:CLOSED`可以继续表示历史工程关闭。一次Opus局部授权也不必撤销全局CODEX委托。

后续应精确区分：已关闭工程身份、当前补充任务状态、当前命名执行者与边界。可使用明确有界overlay及必要validator/test适配；不通过改全局enum/移除校验制造泛化权限。

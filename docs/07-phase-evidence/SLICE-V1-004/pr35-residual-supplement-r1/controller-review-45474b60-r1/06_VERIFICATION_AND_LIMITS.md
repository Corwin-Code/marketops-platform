# 本轮实际执行与边界

## 已执行

- 读取当前附加传输ZIP，检查CRC和40个manifest成员；保留原包不改。
- 读取包内两版CI check-runs、rules、runs/jobs，并按name+app+head检查required集合；B与本轮GitHub读回对应。A的历史检查另存，不替代B。
- 解压并读取B backend/frontend导出日志，抽取准确checkout、summary、步骤结果和浏览器项目/端口/通过；不是JUnit XML逐case重计。
- 通过GitHub只读检查PR、main、Ruleset、实现diff、文档后继、merge parents/tree、backend/frontend jobsteps及关键生产/测试源。
- 读取原件计算Contract/Annex/FrozenSet SHA256和Git blob，与B的返回身份比较。
- 读取准确CodeQL官方query与library；把特定误报告警与当前源码和负测对应。
- 原生等待头8个source-extracted filter逻辑探针实际编译执行，源文件blob绑定；结果只说明这个逻辑链，不是网络/数据库实测。

## 输入形态及限制

上传包中API响应的个人邮箱被标成REDACTED_PERSONAL_EMAIL。manifest证明收到的脱敏文件身份，不把它们叫成未修改的网络原始字节。关键当前状态有连接只读回读支持。

本轮没有重跑完整backend/frontend/browser/CodeQL；没有新建应用数据库、真实HTTP调用或Provider账户；没有修改repo、commit、push、PR正文、Ready或merge。未核验用户工作站clean或具体shell进程；执行人确认归于所提供Owner/工程记录。

本轮不声称重新计算了84个仓库内证据文件的完整SHA目录、全部507条note的源代码路径、所有历史原始日志，或对未知未来版本提供保证。当前接收的CI与源码足以判断原有限修正；明确发现的内部等待缺陷不会因未重跑全套而被忽略。

本输出的manifest/CRC检查只证明材料完整性，不是产品通过或真实平台资格。早期附件虽有过期提示，新包及本轮必要冻结输入已能取得，不要求重传。

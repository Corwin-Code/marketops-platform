# 原有界修正的逐项验收

本表只检查57c5efc4 → 48298206 → 45474b60的授权修正，不再审查全部628项初次分支文件。

| 工作 | 实现和结果 | 本轮处置 |
|---|---|---|
| RC-1后端迁移期望 | 5处0123→0124，88→89、123→124、113→114；旧载荷恢复和checksum断言保留；CI两个job实际clean verify通过 | ACCEPTED |
| RC-2前端lint | 测试回调块体，原断言保留；当前lint及format步骤成功 | ACCEPTED |
| RC-3业务浏览器CI入口 | 新脚本分配非5432 loopback，核对实际端口，统一项目及清理；CI26passed，后续广告12passed | ACCEPTED_FOR_CI_ENTRYPOINT |
| RC-4两处测试SQL | prepareStatement/setObject，同连接时区比较保留并加强行/非空断言；#233/#234在提供的当前状态记录为fixed | ACCEPTED |
| #642 | 花括号澄清，无行为改变；新回归与扫描fixed | ACCEPTED |
| #641、#643—#647 | 精确源码条件、6项新负测以及当前全套CI；多标签case由官方查询getValue()索引0行为支持；未dismiss | ACCEPTED_SCOPED_DISPOSITION_NOT_ALERT_DELETION |
| 历史lint/format等 | append-only erratum，withdraw历史最终源码lint PASS，保留未知执行细节；新通过不倒填旧Head | ACCEPTED_WITH_EVIDENCE_LIMITS |
| 有界权限及执行人 | Owner签发05、结构化点名Opus；A push按Owner在执行会话确认，B由工程方报告执行；准确远程对象可读回 | ACCEPTED_TRANSPORT_WITH_ATTRIBUTION_LIMIT |
| 12项必需CI | 正确Head及GitHub Actions app，完成且success，无缺失；strict规则未放宽 | PASS |
| CodeQL结果 | success；416新结果=6warning+410note，不声称零告警 | PASS_CHECK_WITH_SCOPED_TRIAGE |
| 已发现原生等待头缺陷 | 在旧05外，正确停止并上报；尚未修正 | PRE_MERGE_HOLD |

## 独立CI归属

B的合成merge为a2767e0f1583960c2faa69654c922d00166a6ebc，tree等于B、parents为0f26及B。GitHub check关联Head为B，日志实际checkout合成merge，两者应同时保存而非只选一个。

B的backend-build与backend-integration均执行1907+1370，不相加为两倍独立case。Architecture76与完整backend可能有重叠，不累加为项目唯一测试总数。26业务浏览器与12广告浏览器属于不同suite；418为Vitest，不混为浏览器测试。日志标识`UNKNOWN STEP`是导出形式，实际job步骤结果由原jobs记录确定。

旧历史31小时、418及24+2仍保留原归属。当前B的新418与26/12是新的运行，不能用于证明旧16eda当时lint通过。

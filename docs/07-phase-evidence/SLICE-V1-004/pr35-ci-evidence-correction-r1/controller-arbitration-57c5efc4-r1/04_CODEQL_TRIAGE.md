# PR #35 CodeQL处置表

## 两种集合

CodeQL结果check报告419条PR新告警：2 high、7 warning、410 note。上传的PR ref open清单为516条：2 error/high、19 warning、495 note；其中456条是java/deprecated-call。全分支与PR changed-line集合不能相加，不能把“同一天created_at”直接当作PR新增集合。本包清单来源是投影字段，不包含完整SARIF流。

live `codeql-java`与`codeql-typescript`为success；单独CodeQL结果check为failure。live Ruleset不把该结果check列入12项必需检查，但这不取消安全处置义务。唯一bot review为COMMENTED汇总，所给inline comments为空；没有证据把这一条汇总当作CHANGE_REQUESTED或一个可resolve的inline thread。

## 9条重点告警的源码初筛

| 告警 | 规则及文件 | 本轮源码事实 | 处置 |
|---|---|---|---|
| #233/#234 | concatenated-sql-query / ListingReworkAuthorizationIT:272/276 | 在测试连接上将fixture UUID拼入digest SELECT；不能据此证明生产外部输入可利用 | 参数化两个查询，保留UTC/Taipei相等断言；新scan确认，不dismiss |
| #647 | dereferenced-value-may-be-null / GuardrailService:170 | scope为空时listingReasons必加入LISTING_ACTION_BLOCKED；passed=false，条件表达式不读scope的calibration | 静态所报路径未成立；对应null scope负测和SARIF/source identity核对；可语义保持地显式化守卫 |
| #646 | 同上 / FixedTrafficComparison:58 | boundQualified包含criticalValue!=null，后续只可能变false；doubleValue在true分支 | 静态所报路径未成立；null/不合格criticalValue回归不能变为合格下界 |
| #645 | 同上 / ListingOutcomeSupplyEvidence:45 | scenarios为空先加入gap；循环只在gaps.empty执行，没有先清空gap | 静态所报路径未成立；缺scenario应UNDETERMINED，不用空列表冒充合格 |
| #644 | 同上 / CalibrationService:190 | minimum为空/非数值加入gap；写入minimum只在gaps.empty分支 | 静态所报路径未成立；缺profit basis不提供默认数值 |
| #643 | 同上 / CanonicalScopeMetricService:143 | qualifiedExposureValue(null)=false产生gap；除数访问只在gaps.empty | 静态所报路径未成立；缺总体不可产出比例 |
| #642 | misleading-indentation / PromotionSimulator:197 | nullable null的嵌套if后return在外层null分支；其行为有定义 | 可读性问题；加括号时保持nullable/nonnullable拒绝语义，不新增校验规则 |
| #641 | missing-case-in-switch / ListingDescriptionCommandWorker:137 | pollStatus明确有`case TIMEOUT, UNKNOWN_STATE -> deferObservation(...)`；apply也显式处理UNKNOWN_STATE | “漏UNKNOWN_STATE”的陈述与准确源码不符；查analysis/SARIF/提取版本，不添加盲重试或宽松default消警 |

以上是准确源码控制流核对，不是动态HTTP/数据库攻击或完整扫描flow复现。个别code-scanning alert API被当前连接端点策略拒绝；不能声称本轮读取完整SARIF或GitHub全部告警状态。本轮未修改或dismiss任何告警。

## 新Owner有界修复中允许的结果

high两项用prepared/parameterized查询修复，不排除test目录。七项warning先用对应回归和分析身份核对；若证实误报可记录证据，不强制制造七处改动。不允许警告导致业务缺口被当作零或PASS，不改变Unknown、利润、范围或供应保障要求。

新scan的结果必须绑定新Head及分析版本。实际可达风险若出现，限域修复并补测试；若需要改变已接受权威或信任边界，再提交准确最小差异。不是把“本轮尚未证实实际漏洞”改成“生产安全已证成”。

## 410 note与其余既有告警

保存完整516项投影，记录PR视图的419摘要，维护新增/既有的准确分析基准。deprecated-call等一般维护项可按rule和模块分组进入非阻断技术债，注明责任和触发复核条件；不强制当前全部替换API/升级依赖，不批量dismiss、不降低query pack。与authorization、ownership、gate、protection相关的unused-parameter等先做有限关键路径核对，不能只凭note标签批准后置。

## 官方方法资料（补充，不替代仓库证据）

- https://codeql.github.com/codeql-query-help/java/java-concatenated-sql-query/
- https://docs.github.com/en/code-security/how-tos/manage-security-alerts/manage-code-scanning-alerts/triage-alerts-in-pull-requests
- https://docs.github.com/en/code-security/concepts/code-scanning/code-scanning-alerts

当前官方说明区分analysis job与code-scanning results；查询建议参数化。采用本仓库实际Ruleset和本次结果判断，不推定GitHub默认设置等于本库设置。

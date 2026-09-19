# PR35-NOTE-KEYPATH-01 — 等待头传输缺陷的裁定

## 1. 身份与来源

Exact Head：45474b6039edd46842e1e5f84391dca4264ddc1d。

- `shared/internal/http/BoundedOutboundHttp.java`，blob `337dc3aabad788d0f412cb373937843a3bd7a7b2`：45—47行RESPONSE_HEADERS不含两类原生等待头；响应循环约178—184行只保留白名单、长度≤1024且无控制字符的值。
- `marketplaceintegration/adapter/http/PlatformHttpDescriptionWriteAdapter.java`，blob `aa9a491c1f24c22954edb0ffbb70e916cb7a23ff`：perform调用生产OutboundHttp；classify及filterRetainable已尝试消费/保存三类等待头，但不能恢复下层已经丢失的信息。
- `marketplaceintegration/internal/domain/RetryAfterUnits.java`，blob `84cea0ba55910b08f4fe3fcc088a1bfaf206650c`：原生OZON分钟、WB秒、标准Retry-After秒；混合取最大；不可解析返回空。
- `V0084__persist_provider_description_retry_timing.sql`，blob `4ed1fa5894196885c5509adbc2d79c839cd99ad2`：有效头KNOWN、矛盾/不可解析UNKNOWN、没有头ABSENT；KNOWN推进provider_not_before，UNKNOWN锁住解析；MO092限制lease和attempt。
- `DescriptionRetryHeadersTest.java`，blob `26b9b2fea7237371f42c621ba983938644a878f7`：虽有loopback socket，但用自定义java.net.http OutboundHttp替身，不经过生产BoundedOutboundHttp白名单。

这些路径和单位是准确仓库现行语义；本轮没有再次验证平台当前官方API或调用真实账户。

## 2. 已运行的逻辑反例

| 输入 | 过滤前现行helper秒值 | 生产过滤规则之后 | 含义 |
|---|---:|---:|---|
| OZON Item-Retry-After:2 | 120 | null，头为空 | 确定等待信息丢失 |
| WB X-Ratelimit-Retry:120 | 120 | null，头为空 | 同根 |
| OZON原生2 + Retry-After:1 | 120 | 1 | 原生更长等待被缩短 |
| WB原生120 + Retry-After:1 | 120 | 1 | 同根 |
| 只有标准Retry-After:120 | 120 | 120 | 正向对照 |
| 仅Set-Cookie | null | null | 非业务敏感头仍应过滤 |
| 畸形原生值bad + 标准1 | null | 1 | 不确定被伪装成确定短等待 |
| 超长标准数字 + 原生2 | null | null，头为空 | 连“有但无法解释”也可能丢失 |

共8个逻辑场景达到探针对**当前行为**的预期，不是8项产品验收通过。probe使用逐字节身份匹配的RetryAfterUnits及从准确生产源提取的过滤循环。没有启动Apache HTTP、Spring、数据库或真实Provider。数据库后果依据SQL控制流，不是本轮动态数据库实测。其他已有更晚等待仍可能阻断，不能声称上述任意响应都必然引发一次真实重复写。

## 3. 对交接术语的准确限定

“原生头应UNKNOWN”不适用于所有情况：合法`2分钟/120秒`应保留为KNOWN；畸形、重复歧义、单位不明或超出表示范围才应UNKNOWN。真正缺少头才可ABSENT。修复不能把三者混在一起。

“默认60秒”仅为有关worker后续路径的可能回退，不足以概括所有控制流。混合头120→1提供了更直接的下限丢失反例，不需要假定默认值或真实账户。

## 4. 根因归属和关闭效力

这不是普通维护note，也不是F-W01/F-W02/E-04未具备。它是已实现控制链的内部信息丢失，落在原S4-DR-R1-022“实际等待不得缩短、保留响应证据”的同根传递范围。原027的证据充分性对应受限定。保留原冻结Set和Controller/Owner原件，不新增第028项，不重开其它25项。

登记`CRCF-PR35-04`：此前Controller接受了绕过生产传输的测试作为完整等待链证明，未把共享白名单与实际消费者核对完整。工程方本次检查主动发现且因授权范围有限停止是正确行为；不将遗漏转给Owner或以“新CI才出现”隐藏其早已存在。

新平台写默认关闭限定当前暴露；它不证明代码条件合格。Controller当前不批准带已知等待约束缺陷的候选进入Ready/merge。

## 5. 一次限域关闭条件

- 让所需原生/标准等待信号经过实际生产传输、adapter和持久化消费者，不扩成保留所有响应头。
- 合格等待取完整适用的最迟not-before；真缺失与被丢弃/畸形不可混淆；重复大小写、重复值、超长、单位不符、溢出保留可核验的不确定性，不能默认变零、短等待或无头。
- 不得为保留等待扩大SSRF、重定向、自动重试、cookie、secret、响应大小/时间边界。不可表示的原始值可以有界隔离或失败，但不能伪造原始头/静默截成可执行值。
- 使用隔离loopback但**实际BoundedOutboundHttp过滤路径**、真实adapter及必要数据库/worker联测，证明Known/Unknown/Absent、混合较长等待、当前fence及重启后不得提前；批准有效期不得因此延长，未决写不得自动重提。
- 合格的既有其他共享HTTP消费者及敏感头过滤需回归。源码通过/测试通过/真实平台资格分别报告。

没有要求本轮实际调用任何Ozon/WB接口或取得Gate EV/E。

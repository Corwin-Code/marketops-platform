# 原始运行证据核验（R2完成）

## A. 传输与索引

输入ZIP：`SLICE-V1-004-Final-Closure-Continuation-39d55e30-R1-evidence.zip`；SHA-256 `a2b81c768a758ad7419b797e1e588592de29c67b0b5e1af39f22a09709d43930`。旧索引31项、新索引28项全部存在、逐文件SHA-256匹配；原目录和失败记录保留，未修补或重新生成历史证据。完整清单见`evidence/INPUT_VERIFICATION.json`。

| 原始层次 | 独立读取结果 | 使用范围 |
|---|---|---|
| d65主回归C02 | 6份XML；unit 24（6+5+6+7），integration 13（12+1）；failure/error/skip/flaky/rerun均0；日志BUILD SUCCESS、exit=0 | 原003及关联回归；不是全后端套件 |
| d65 C01预检 | 原日志24项unit及BUILD SUCCESS | 预检；其XML已被C02同名套件后续运行替代，不重复计为额外24个独立case |
| d65 C03架构 | 原日志汇总76、failure/error/skip均0、BUILD SUCCESS、exit=0 | 架构边界回归；未取得其逐case XML，不伪称XML重计 |
| 6cc T01 | 原日志21unit+9integration；对应基础XML及9个case可核对 | 原SUMMARY桥接工程证据；九个IT均在d65再次运行 |
| 6cc T02迁移/schema | 原日志3unit+21integration；两份IT XML为11+10；日志应用124份迁移、BUILD SUCCESS | 124份迁移字节未变，沿用该作用域，不将此3个重复unit又累加到新测试数 |
| 6cc T03旧总量重算 | 原日志3unit+1integration及对应IT XML | 保留历史；同重算方法在d65复跑，合格SUMMARY修订由新增journey独立覆盖 |
| 6cc前端 | JSON含1个testResults文件、26条assertion均passed；原日志26/26；typecheck及最终Prettier输出支持checkpoint退出0声明 | 源码126/126未变而继承；初始格式失败保留 |
| governance/readiness | 两阶段原输出在包内；新日志分别含governance exit=0与readiness exit=0、TC-GLOBAL-001—004 PASS | 静态治理检查，不称为生产能力实证 |

## B. 三条新增实际运行case

`TEST-com.mimococo.marketops.ListingReworkAuthorizationIT.xml`本次包含12个case，均无失败/错误/跳过。新增case与此前审阅的准确源码名称一致：

1. `infeasibleCriticalGroupComplementCannotBorrowQualifiedProtectionThroughTheFormalOutcome`；运行时间1.103s。
2. `infeasibleCriticalGroupComplementKeepsTheProvenSourceStrataWhileLawfulComplementsQualify`；0.461s。
3. `qualifiedSummaryLateFactRevisesTheFormalOutcomeThroughRecalculationOnce`；1.375s。

它们与原九个case的差集恰为上述三项。XML `it.test`选择集合和实际13项IT case集合相等；未将“只编译IT”或“跳过测试但退出0”的调用计为验证。

## C. 运行上下文

新C02日志第193—213行记录Testcontainers、local Unix socket、独立PostgreSQL 17.6镜像及localhost动态端口；XML记录Java 21.0.10、Mac OS X aarch64及实际test/it.test选择。第156行unit总数24，第385行IT总数13，第396行BUILD SUCCESS，第401行exit=0。测试完成于日志所记2026-09-16T16:59:01+08:00；这是实施者运行时间，不是Controller重跑时间。

迁移/schema的旧T02使用原日志记录的PostgreSQL 18.4；不把不同测试环境统一伪写为同一版本。新选择使用`-Djacoco.skip=true`，因此不生成当前Head全量覆盖率；历史覆盖率仍只属于原完整运行。

合成profile、计划时间、会计保护及`supersedes_fact_id`预置边界沿先前源码审阅保留。本次接受的是签名HTTP和实际隔离数据库消费者的工程运行证据，不是来源语义被真实平台证实。

## D. 独立工作与限制

本轮由Controller自行执行ZIP/CRC/SHA-256、XML计数和选择集合核对、日志检查、清单比较，40项明确材料/证据检查通过。未执行产品源码修改、应用suite、浏览器、真实账户调用或生产迁移。

`evidence/RAW_REPORT_RECOUNT.json`保留逐case计数、日志行号和非秘密环境摘录；它没有被回填进原始XML。完整原始文件在`inputs/SLICE-V1-004-Final-Closure-Continuation-39d55e30-R1-evidence.zip`中。

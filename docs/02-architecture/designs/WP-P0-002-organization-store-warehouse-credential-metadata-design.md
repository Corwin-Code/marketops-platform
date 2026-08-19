# WP-P0-002 设计文档 v1.2 — Organization, Store, Warehouse & Credential Metadata

> 项目:MarketOps Russia(Russia Marketplace Operations & Decision Platform)
> 工作包:WP-P0-002 — Organization, Store, Warehouse & Credential Metadata
> 文档性质:本工作包的**当前功能设计**(DESIGN_ONLY 交付物,非实施结果)。
> 全文只描述当前设计行为、不变量、理由与失败/恢复语义;版本沿革仅保留于下方
> 文档控制元数据。

```text
Work Package:                 WP-P0-002
Source repository:            Corwin-Code/marketops-platform
Source main SHA:              3c4f6a6210db377b5471d6014da6afd5bfef6127
Source main tree:             dd505f02539ddd755c805ac7d0793c4a77963eed
Design revision:              1.2
Replaces:                     Design v1.1 (SHA-256 beddf819f1a33503cd935dca4d805fb215fa76b0990809f315c120b797f84667)
                              Design v1.0 (SHA-256 7a58df858141e79f865d8494e3c5c307837531ec3a3b13fb65087ecb6a3356e0)
Status:                       READY_FOR_CONTROLLER_REVIEW
Authorization used:           DESIGN_ONLY
Implementation status:        NOT_STARTED
Implementation authorization: NONE
Production writes:            DISABLED
Secret / PII declaration:     本设计全程未使用、未请求、未存储任何 Secret、Token、
                              API Key、口令、Cookie、买家 PII 或生产载荷;
                              未连接任何真实 Marketplace 账户。
Requested next verdict:       CONTROLLER_DESIGN_REVIEW
```

本文档不含任何实施授权语义。任何后续实施必须以独立 Controller 对**本确切文件**
(以 SHA-256 绑定)作出 `APPROVED_FOR_IMPLEMENTATION` 裁定为前提。

---

## 1. Design metadata and source binding(设计元数据与源绑定)

### 1.1 基线核验

对权威仓库执行只读克隆与核验(首验 2026-08-17;v1.2 前经 `git ls-remote` +
`git fetch origin main` 再核验 2026-08-18,`STATE_DELTA = NONE`):

```bash
git clone https://github.com/Corwin-Code/marketops-platform.git
git checkout 3c4f6a6210db377b5471d6014da6afd5bfef6127
git log -1 --format='%H %T %s'
# → 3c4f6a6210db377b5471d6014da6afd5bfef6127 dd505f02539ddd755c805ac7d0793c4a77963eed
#   docs: activate WP-P0-002 design gate (#9)
```

| 期望值(任务规约) | 观测值 | 结论 |
| --- | --- | --- |
| `main SHA = 3c4f6a62…` | `3c4f6a6210db377b5471d6014da6afd5bfef6127` | 一致 |
| `main tree = dd505f02…` | `dd505f02539ddd755c805ac7d0793c4a77963eed` | 一致 |
| `active_work_package: WP-P0-002` | `WP-P0-002` | 一致 |
| `active_gate: READY_FOR_DESIGN` | `READY_FOR_DESIGN` | 一致 |
| `authorization: DESIGN_ONLY` | `DESIGN_ONLY` | 一致 |
| `production_write_enabled: false` | `false` | 一致 |

**核验结论:`STATE_DELTA = NONE`,无未解决的源冲突。** 本轮未发现任何 live 规范源与
任务提示词的冲突,因此不触发 `CONTROLLER_DESIGN_HANDOFF_REVIEW`。

### 1.2 按强制阅读顺序读取的仓库文件(全部 @ `3c4f6a62`)

| # | 文件 | blob SHA(节选) |
| --- | --- | --- |
| 1 | `docs/00-governance/CURRENT_STATE.md` | `07d36c02` |
| 2 | `docs/00-governance/CONTROLLER_REVIEW_STANDARD.md` | — |
| 3 | `docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md` | — |
| 4 | `docs/00-governance/AI_OPERATING_MODEL.md` | — |
| 5 | `docs/00-governance/HANDOFF_PROTOCOL.md` | — |
| 6 | `docs/03-work-items/WP-P0-002-organization-store-warehouse-credential-metadata.md` | `27abc593` |
| 7 | `docs/03-work-items/BACKLOG-PHASE-0.md` | — |
| 8 | `docs/01-requirements/traceability.csv` | `bd0ce9f4` |
| 9 | `docs/00-governance/OPEN_QUESTIONS.md` | `dc7f5dd9` |
| 10 | `docs/01-requirements/baseline-v1.0-cn.md`(产品原则/硬规则、领域模型、系统上下文与模块/Schema 基线、账户接入与 Capability Discovery、IAM-001/004/006/007、INT-002/003、ADM-001/002、安全/Secret/审计、测试/迁移/验收章节) | `d3c17894` |
| 11 | `ADR-0001` / `ADR-0003` / `ADR-0004`(以及 ADR-0002 作为上下文) | — |
| 12 | `backend/marketops-server/pom.xml` | `ec622999` |
| 13 | `backend/marketops-server/src/main/resources/application.yaml`(及 `-local` / `-ci`) | `203040ce` |
| 14 | `backend/marketops-server/src/main/resources/db/migration/V0001__create_foundation_schemas.sql` | `39344905` |
| 15 | `shared` 模块全部源文件(`CorrelationId`、`ErrorCode`、`internal/config/{WebConfig,CorsProperties}`、`internal/correlation/CorrelationIdFilter`、`internal/errors/GlobalExceptionHandler`、`internal/logging/EcsCorrelationIdJsonMembersCustomizer`) | — |
| 16 | `adminobservability` 模块全部源文件(`MetaStatusController`、`internal/{MetaStatusAssembler,MetaStatusResponse}`) | — |
| 17 | 架构测试全部源文件(`ArchitectureRules`、`CodeQualityArchitectureRules`、`ModuleBoundaryArchitectureTest`、`ModulithArchitectureTest`、`RuleSensitivityArchitectureTest` 及 `testfixture/**`) | — |
| 18 | 数据库测试(`FlywayMigrationIT`、`DatabasePrivilegeIT`、`PostgresContainerSupport`)与应用测试(`ApplicationConfigurationTest`、`ApplicationEnvironmentFailClosedTest`、`LoggingContractTest`、`ApplicationSmokeIT`) | — |
| 19 | `infra/compose/postgres-init/{01-init-roles.sh,sql/01-roles.sql}` | — |
| 20 | `scripts/validate_production_readiness.py`、`scripts/validate_governance.py`(三条全局硬规则与治理校验器的实际检查面) | — |
| 21 | `docs/00-governance/QUALITY_GATES.md`、`docs/02-architecture/designs/WP-P0-001-foundation-design.md`、`docs/01-requirements/naming-baseline-cn.md`、`docs/05-testing/TEST_STRATEGY.md` | — |
| 22 | 前端 `frontend/marketops-console` 的 health/console shell(仅确认边界;本设计**不**提出前端界面,见 §15.6) | — |


### 1.3 已接受 ADR

- ADR-0001 — Modular Monolith and Technology Baseline(ACCEPTED)
- ADR-0002 — Immutable Raw and Ledgers(ACCEPTED;本 WP 仅作上下文,不实现 Raw)
- ADR-0003 — Read First, Controlled Write Later(ACCEPTED)
- ADR-0004 — AI Maker–Checker Development Model(ACCEPTED)

### 1.4 外部一手来源(本轮实际使用)

本设计**不需要**任何 Ozon/Wildberries 平台事实(全部延后至 WP-P0-005/006,见 §12、§25)。
本轮唯一使用的易变外部技术事实:

| # | 事实 | 来源 | 核验日 | 分类 |
| --- | --- | --- | --- | --- |
| V-101 | PostgreSQL 18 的 `btree_gist` 模块为 **trusted** extension:"This module is considered "trusted", that is, it can be installed by non-superusers who have CREATE privilege on the current database." 且其 GiST operator class 支持标量列参与 Exclusion Constraint | `https://www.postgresql.org/docs/18/btree-gist.html` | 2026-08-17 | FACT(官方一手) |

该事实支撑 §7.2 / §17.3 的生效区间防重叠 Exclusion Constraint 决策:`marketops_migration`
持有数据库级 `CREATE` 权限(见 `infra/compose/postgres-init/sql/01-roles.sql` 与
`V0001` 实测),因此**无需超级用户**即可 `CREATE EXTENSION btree_gist`。

其余全部设计输入均来自仓库内规范源。**无任何平台 cardinality、endpoint、订阅、
限流或费用事实被猜测或引用。**

### 1.5 未解决的源冲突

无。

---

## 2. Goals, non-goals and invariants(目标、非目标与不变量)

### 2.1 目标(对规范 WP 的简明重述,未作更改)

为后续 Connector、Raw、PIM、Order、Inventory、Finance 工作包设计一个**平台中立、
可演进、可审计**的元数据基础,提供以下对象的显式身份与关系:Organization、
Legal Entity、Marketplace Account、Store、Warehouse、Store↔Warehouse 服务/履约关联、
授权范围与权限分类、Service Account 元数据、不透明 Credential 引用、
Endpoint/Capability Registry、验证/出处状态、Feature Flag 与 Capability 元数据、
append-only 元数据变更审计责任,以及受控的 ADM-001 维护/查询行为。

产出是**生产级设计与有界实施契约**。它不得暗示已建立任何真实 Ozon/Wildberries
连接、Credential 或当前业务清单。

### 2.2 非目标(显式)

- 平台中立元数据基础**之外**的一切业务能力;
- 无真实 Marketplace 连接、无 Marketplace HTTP Client、无 Capability 声明;
- 无 Secret 检索、无 Secret Manager 选型/连接(OQ-006 OPEN);
- 无运行时 IAM 实现:无登录、MFA、Session、Token、RBAC/ABAC 执行(OQ-005 OPEN);
- 无 Raw/Worker/PIM/Order/Inventory/Finance 事实与表;
- 无生产写:`production_write_enabled=false` 保持并被本设计加固(§13);
- 无猜测的平台事实(OQ-102 OPEN;§12 的 Registry 只承载 `UNKNOWN`/`UNVERIFIED`);
- 无隐藏第九 schema:全部对象映射到既有八 schema(§4);
- **无属于 WP-P0-002 的工程决策被推迟**:本文对每一项范围内工程选择都给出唯一
  具体决策与取舍理由,不提交"选项菜单"。

### 2.3 实施/测试不变量(I-01 … I-14)

后续实施必须使下列不变量全部由确定性测试证明(测试映射见 §23):

```text
I-01  通用 Domain 不编码任何平台无关的 1:1 基数假设;Legal Entity 数量不设上限;
      Warehouse 不是 Store 的严格子节点。
I-02  身份与 native key 唯一性、引用完整性由数据库约束确定性保证。
I-03  跨 Organization 关系在数据库层与应用层双重拒绝,并留下审计。
I-04  权限/范围授予不能隐式放大特权;deny-by-default。
I-05  Service Account 的过期、禁用、未知状态一律 fail closed。
I-06  明文 Secret 不出现在持久化、API/UI、日志、异常、Tracing、迁移、fixture、
      测试失败输出、证据与文档示例中;库中只存不透明引用与非密元数据。
I-07  UNKNOWN / UNVERIFIED Capability 一律 fail closed 并保留出处;
      未知状态不被静默强制为 false/available/平台默认值。
I-08  production_write_enabled 保持 false;任何元数据 Flag 不能覆盖它;
      将其配置为 true 会导致应用启动失败(本阶段无 Controlled Write Gate)。
I-09  每次元数据变更记录 actor/source、服务端时间、entity、action、安全变更表示、
      correlation id;拒绝性尝试同样留痕。
I-10  审计记录经批准的应用边界 append-only;应用角色无 UPDATE/DELETE 权限。
I-11  迁移只增不改;V0001 不可变;干净库与自 WP-P0-001 升级两条路径产生相同终态。
I-12  数据库角色保持对象级最小权限;零笼统/默认授权;应用角色对任何表无 DELETE。
      (元数据纠错走 forward correction,不走物理删除。)
I-13  架构边界防止平台 Client/SDK 类型与 Secret 语义进入通用 Domain 与模块公共 API。
I-14  ADM-001 维护/查询面不可作为公网未认证变更面到达:loopback 绑定 + 维护写开关
      fail closed + 操作者归因强制。
```

```text
DEFERRED_ITEMS_IN_WP_SCOPE: NONE
COMPROMISE_IMPLEMENTATION_ALLOWED: NO
```

---

## 3. Module / package architecture(模块与包架构)

### 3.1 模块划分(一个可部署单元内的封闭模块)

沿用既有 Modular Monolith 风格:模块 = 根包 `com.mimococo.marketops` 的直接子包,
`internal` 子包私有,公共 API = 模块根包 + 显式 `@NamedInterface` 包。**不新建微服务、
不新建第二个可部署单元、不引入 ORM。**

| 模块 | 状态 | 职责(本 WP) | 对其它模块暴露的 API |
| --- | --- | --- | --- |
| `shared` | 扩展 | 既有关联/错误原语之上追加:新增稳定 `ErrorCode` 常量(§16.4)、`MetadataMaintenanceProperties` + `MaintenanceWriteGate`(维护写开关,§15)、`ProductionWritePolicy`(生产写门,§13)、`IdGenerator`(注入式 UUID 生成,满足既有"禁环境时钟/随机直读"风格) | 根包:`ErrorCode`(扩展)、`MaintenanceWriteGate`、`ProductionWritePolicy`、`IdGenerator`;实现留在 `shared.internal` |
| `adminobservability` | 扩展 | 既有 meta status 之上追加:**元数据审计的唯一所有者**——审计写入端口、审计查询边界、`ops.metadata_audit_event` 的仓储(§14) | 既有根包 + 新增 `@NamedInterface("audit")` 包 `adminobservability.audit`:`MetadataAuditRecorder`、`MetadataAuditQueries`、`MetadataAuditEntry`、`AuditAction`、`AuditActorType`、`AuditEntityType`、`AuditEventFilter` |
| `organizationaccount` | 新建 | Organization、Legal Entity、Marketplace Account、Store、Warehouse、Store↔Warehouse 关联、Store 履约模式声明、时区/币种元数据、**Marketplace 平台身份参考(`core.marketplace_platform`:平台身份属账户所有权链的上游标识)**、其 ADM-001 维护/查询面 | 根包:`OrganizationDirectory`(跨模块目录查询:实体存在性、组织归属、状态、**平台参考码查询**;供 identityaccess / marketplaceintegration 校验引用)+ 只读引用 record(`OrganizationRef`、`LegalEntityRef`、`MarketplaceAccountRef`、`StoreRef`、`WarehouseRef`、`MarketplacePlatformRef`) |
| `identityaccess` | 新建 | 授权范围分类、Read/Write/Finance/Ads/Credential Admin 权限分类、Service Account 元数据、范围授予(scope grant)、其维护/查询面 | 根包:`AccessMetadataDirectory`(Service Account 状态评估:`evaluate(serviceAccountId) → ACTIVE / EXPIRED / DISABLED / REVOKED / UNKNOWN`,供未来 WP-P0-003 Job 归属证明消费) |
| `marketplaceintegration` | 新建 | 不透明 Credential 元数据/引用、Endpoint/Capability Registry、Capability 验证/出处、账户级 Capability 状态、Feature Flag 元数据、其维护/查询面;未来 `adapter.<platform>` 的预留归宿 | 根包:`CapabilityDirectory`(`isUsable(capability) → 恒 fail closed 判定`)、`FeatureFlagDirectory`(flag 状态只读查询)。**本 WP 不创建任何 `adapter.*` 包** |

### 3.2 模块依赖方向(无环)

```text
organizationaccount   ──▶ adminobservability(audit)──▶ shared
identityaccess        ──▶ organizationaccount、adminobservability(audit)、shared
marketplaceintegration──▶ organizationaccount、adminobservability(audit)、shared
shared                ──▶ (无业务模块;保持依赖叶)
```

- `identityaccess → organizationaccount`:scope grant 必须校验资源存在且组织一致;
- `marketplaceintegration → organizationaccount`:Credential/Capability 的 account/store
  归属校验与平台参考码解析(平台身份参考由 organizationaccount 拥有);
- `marketplaceintegration` **不依赖** `identityaccess`:Credential 用途分类是
  `marketplaceintegration` 自有的参考表(`platform.credential_purpose`,§11.1),与
  IAM 权限分类**语义分离**,两者之间不存在需要对齐的枚举;Registry 也不持久化
  Capability→内部权限映射(§12.5a),`platform` 持久层与 `iam` 零引用;
- 审计方向:三个业务模块 → `adminobservability.audit`(同事务端口调用,§14.3)。

该图无环,`shared` 仍是叶。**持久层依赖方向与 Java 模块方向一致**:
`platform.*` 表只引用 `core.*` 与 `platform.*` 自身(与 marketplaceintegration →
organizationaccount 一致;**对 `iam.*` 零引用**),`iam.*` 表只单向 FK 引用
`core.*`;`core.*` 不引用 `iam.*`/`platform.*` 的任何表。迁移文件依赖因此恰为
V0005→V0004 与 V0006→V0004(§4.3)。Spring Modulith `verify()` 与既有 7 条
ArchUnit 规则自动覆盖新模块(规则以根包参数化,无需修改规则本身)。

### 3.3 模块内部布局(每个新业务模块)

```text
com.mimococo.marketops.<module>/
├── <PublicDirectory>.java              模块公共 API(接口 + 只读 record)
├── package-info.java
└── internal/
    ├── domain/                         领域对象(不可变 record/枚举)、状态机、不变量
    ├── application/                    应用服务:事务边界、校验、审计端口调用
    ├── infrastructure/jdbc/            纯 Spring JDBC(JdbcClient)仓储实现
    └── web/                            ADM-001 REST controller + 请求/响应 DTO
```

- **Repository 跨模块访问的防止**:仓储类全部位于 `internal.infrastructure`,由既有
  "module internals are reachable only from that exact module" 规则(TC-ARCH-001 族)
  与 Modulith 封装双重拒绝;跨模块只能走根包目录接口。
- **vendor DTO/SDK 隔离**:本 WP 不引入任何 vendor SDK;既有规则(vendor 类型仅限
  `marketplaceintegration.adapter.<platform>`、不得出现在 Domain/模块 API 签名)
  从第一天守卫未来适配器。Registry/Credential 的全部模型为平台中立自建类型。
- **Web 层不触库**:controller 只依赖应用服务(既有 CodeQuality 规则守卫)。
- **层规则激活**:`..domain..`/`..application..`/`..port..` 模式的既有规则在新模块
  的对应包出现后自动获得真实主体;实施时必须按 WP-P0-001 设计的清理触发器约定,
  评估相应 `allowEmptyShould(true)` 放宽是否可以收回(§26 C3 明确此项)。

### 3.4 与 WP-P0-003 / 005 / 006 的未来集成边界

| 未来 WP | 消费本设计的边界 | 本 WP 不做 |
| --- | --- | --- |
| WP-P0-003(Job/Cursor/Raw) | Job 定义引用 `iam.service_account`(经 `AccessMetadataDirectory` 评估,证明 Job 不用个人账户);Cursor 归属 `platform` schema 由该 WP 自行新增 | Job Schedule、Backfill、Worker、Outbox |
| WP-P0-005(Ozon Read) | 以验证证据填充 Registry(§12.6 的 `UNVERIFIED→VERIFIED` 迁移 + 出处事件);创建 `marketplaceintegration.adapter.ozon` | 任何 Ozon 事实/Client |
| WP-P0-006(WB Read) | 同上(wildberries) | 任何 WB 事实/Client |
| 未来运行时 IAM WP | 消费 scope/permission/service account 元数据作为策略源(§9.7) | 认证、会话、运行时执行 |
| 未来 Credential 运行时 WP(OQ-006 后) | 经 §11.6 的 Secret 解析端口边界实现真实 Secret Manager 适配 | Secret 检索 |

---

## 4. Data / schema allocation(数据与 Schema 分配)

### 4.1 分配总表(仅既有八 schema;不提议第九 schema)

`audit` 保持概念责任。全部新对象映射到既有 schema,与 Baseline 的 schema 语义
(`iam`=用户/角色/Scope/Service Account;`platform`=Endpoint/Capability/Credential
Metadata;`core`=跨平台统一业务实体;`ops`=任务/审批/告警/**审计**)逐字对齐:

| Schema | 新表 | 归属模块 |
| --- | --- | --- |
| `core` | `marketplace_platform`(参考表:平台身份)、`organization`、`legal_entity`、`marketplace_account`、`store`、`warehouse`、`store_warehouse_link`、`store_fulfillment_declaration`、`fulfillment_mode`(参考表) | organizationaccount |
| `iam` | `permission_kind`(参考表)、`service_account`、`service_account_allowed_source`、`service_account_scope_grant` | identityaccess |
| `platform` | `credential_purpose`(参考表)、`credential_metadata`、`credential_store_scope`、`platform_capability`、`platform_endpoint`、`capability_subject_status`、`platform_permission_requirement`、`capability_verification_event`、`feature_flag` | marketplaceintegration |
| `ops` | `metadata_audit_event` | adminobservability |
| `raw` / `staging` / `ledger` / `mart` | **零对象**(分别属于 WP-P0-003+ / 分析 WP) | — |

分配理由:审计物理落位 `ops` 直接来自 Baseline 对 `ops` schema 的定义("任务、建议、
审批、执行、告警和**审计**"),因此**不需要**第九 schema;`marketplace_account` 落
`core` 而非 `platform`,因为它是所有权链上的跨平台统一业务实体;**平台身份参考
`marketplace_platform` 同理属所有权链的上游标识(账户"在哪个平台"是账户身份的
组成部分),落 `core` 并由 `organizationaccount` 拥有——`platform` schema
保留给平台技术面(Capability/Endpoint/Credential 及未来 Cursor),其表只单向
引用 `core.*`,持久依赖因此无环且与 Java 模块方向一致。**

### 4.2 迁移方向与基线约束

- **只增量**:V0001 不可变;不修改、不重编号既有迁移;无破坏性迁移假设;
  Flyway Community forward-fix only;
- 迁移中**零远程调用**、零环境探测;
- 参考表种子(§21.4)为**确定性合成数据**:固定 code 主键的枚举行,不含任何业务
  清单、平台事实或生产数据;
- 干净库(V0001→V0006)与自 WP-P0-001 库升级(已有 V0001,再施加 V0002–V0006)
  两条测试路径必须产生按 `pg_dump --schema-only` 归一化比较等价的终态(§23 TC-DB-215)。

### 4.3 迁移文件规划(计划级,本任务不创建)

| 文件 | 内容 | 前置 |
| --- | --- | --- |
| `V0002__enable_btree_gist_extension.sql` | `CREATE EXTENSION btree_gist;`(trusted,V-101;由 `marketops_migration` 执行,其持有数据库级 CREATE) | V0001 |
| `V0003__create_metadata_audit_event.sql` | `ops.metadata_audit_event` + 索引 + 对象级授权(INSERT、SELECT;**无 UPDATE/DELETE**) | V0001 |
| `V0004__create_core_organization_metadata.sql` | **先建 `core.marketplace_platform`(含 OZON/WILDBERRIES 种子)与 `core.fulfillment_mode`(含种子)**,再按 FK 依赖序建 `organization → legal_entity → marketplace_account → store / warehouse → store_warehouse_link / store_fulfillment_declaration`;全部约束、Exclusion Constraint、索引、对象级授权 | V0002 |
| `V0005__create_iam_access_metadata.sql` | `iam.*` 全部表、约束、索引、`permission_kind` 种子、对象级授权 | V0004(FK → core) |
| `V0006__create_platform_registry_metadata.sql` | `platform.*` 全部表(`credential_purpose` 种子先行,再 `credential_metadata → credential_store_scope`、`platform_capability → platform_endpoint → capability_subject_status / platform_permission_requirement → capability_verification_event`、`feature_flag`)、约束、索引、对象级授权 | V0004(FK → core) |

**FK 目标先于 FK 存在的证明**:V0004 内部创建序即依赖序;跨文件仅
V0005/V0006 → V0004(单向);`core.*` 不引用 `iam.*`/`platform.*`。干净安装
(V0001→V0006)与升级路径(V0001 既有,再 V0002→V0006)的执行序相同、结果
相同(TC-DB-215)。每个迁移文件自带其对象的全部授权语句(与 V0001 的"授权随
引入对象的变更走"注释约定一致);失败时 PostgreSQL 事务性 DDL 整体回滚,
不留部分对象。

---

## 5. Domain / metadata model(领域与元数据模型)

### 5.1 通用建模决策(适用于全部聚合)

| 维度 | 决策 |
| --- | --- |
| 稳定身份 | 应用侧经注入的 `IdGenerator` 生成 UUID(v4)作为代理主键;对外 API 只暴露 UUID 与业务 code,天然抗枚举猜测。元数据量级低,UUIDv4 的索引局部性代价可忽略;不依赖 PG 18 `uuidv7()` 以保持身份生成可在 Domain 测试中确定性注入 |
| 业务 code | 每个实体带作用域内唯一、操作者赋予的 `code`(`^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$`),创建后**不可变**(重命名 = 改 `display_name`);code 提供人类可读引用与创建重试幂等锚点 |
| 组织所有权 | 除 `organization` 自身与参考表外,每张实体表携带 `organization_id`,并经**复合 FK**(§17.2)保证与父级一致——跨组织关系在数据库层不可表示 |
| 生命周期 | 显式 `status` 列 + 应用层状态机(§18);无物理删除 |
| 审计时间戳 | `created_at` / `updated_at`(`timestamptz`,UTC,应用经注入 Clock 提供) |
| 并发 | `version bigint`(乐观锁);更新必须携带 `expectedVersion`,失配 → `VERSION_CONFLICT` |
| 必填语义 | 未知业务事实建模为 NULL(= UNKNOWN,查询层显式暴露为 UNKNOWN)而不是猜测默认值;结构性字段(code、display_name、外键、status)NOT NULL |
| 关系 | 全部 FK 显式;关联表独立成表,不用数组内嵌引用 |

### 5.2 聚合清单(实施就绪级)

以下每个聚合的允许/拒绝迁移见 §18,约束/索引见 §17。

**`core.organization`** — 顶层经营主体。
`id`、`code`(全局唯一)、`display_name`、`default_timezone`(nullable,IANA)、
`default_currency_code`(nullable,ISO-4217)、`status`(ACTIVE/SUSPENDED/RETIRED)、
`created_at`、`updated_at`、`version`。

**`core.legal_entity`** — 法律主体,Organization 之下。
`id`、`organization_id`、`code`(组织内唯一)、`display_name`、`registered_name`
(nullable)、`country_code`(nullable,ISO-3166-1 alpha-2,应用校验)、`status`、
时间戳、`version`。当前实际数量为 1(OQ-101),**模型不设上限**(I-01)。
税号/注册号等业务登记字段不在本 WP(属 OQ-101 onboarding 输入,后续增量迁移追加,
不阻塞本设计)。

**`core.marketplace_platform`**(参考表)— 平台身份参考,账户所有权链的
上游标识。`code`(PK:`OZON`、`WILDBERRIES`,确定性种子)、`display_name`、
`status`(ACTIVE)。后续平台以增量迁移追加;`code` 永不复用。由
`organizationaccount` 拥有,维护面只读(种子由迁移写入)。

**`core.marketplace_account`** — 平台账户,归属 Legal Entity。
`id`、`organization_id`、`legal_entity_id`、`platform_code`(FK →
`core.marketplace_platform`,同 schema 内引用)、`code`、`display_name`、
`native_account_key`(nullable,不透明保存,语义 UNKNOWN 直至平台 WP 核验)、
`status`、时间戳、`version`。
一个 Legal Entity 可持有任意多个账户;账户↔Store 基数不受限(I-01)。

**`core.store`** — 店铺,归属 Marketplace Account。
`id`、`organization_id`、`marketplace_account_id`、`code`、`display_name`、
`native_store_key`(nullable 不透明)、`timezone`(nullable IANA)、`currency_code`
(nullable ISO-4217)、`status`、时间戳、`version`。时区/币种由操作者录入并
经 §7.4 校验,系统不猜测平台默认值。

**`core.warehouse`** — Legal Entity 拥有的运营节点。**不是 Store 的子节点。**
`id`、`organization_id`、`legal_entity_id`、`code`、`display_name`、`timezone`
(nullable IANA)、`status`、时间戳、`version`。

**`core.store_warehouse_link`** — Store↔Warehouse 可配置服务/履约关联(M:N)。
`id`、`organization_id`、`store_id`、`warehouse_id`、`fulfillment_mode_code`(FK →
`core.fulfillment_mode`)、`effective_from`(NOT NULL)、`effective_to`(nullable =
开区间)、`status`(ACTIVE/ENDED/CANCELLED)、`note`、时间戳、`version`。
同 (store, warehouse, mode) 的 ACTIVE 生效区间不得重叠(§7.2)。

**`core.store_fulfillment_declaration`** — Store 层履约模式声明(独立于仓库关联;
Marketplace-fulfilled 可以在没有任何本地仓库关联时成立)。
`id`、`organization_id`、`store_id`、`fulfillment_mode_code`、`effective_from`、
`effective_to`、`status`、时间戳、`version`。同 (store, mode) 区间不重叠。

**`core.fulfillment_mode`**(参考表)— §8。

**`iam.permission_kind`**(参考表)、**`iam.service_account`**、
**`iam.service_account_allowed_source`**、**`iam.service_account_scope_grant`** — §9、§10。

**`platform.credential_purpose`**(参考表)、**`platform.credential_metadata`**、
**`platform.credential_store_scope`**、**`platform.platform_capability`**、
**`platform.platform_endpoint`**、**`platform.capability_subject_status`**、
**`platform.platform_permission_requirement`**、
**`platform.capability_verification_event`**、**`platform.feature_flag`** —
§11、§12、§13。

**`ops.metadata_audit_event`** — §14。

**平台特定 DTO 不作通用 Domain 模型**:上述全部类型为自建平台中立类型;native key
一律以不透明 `text` 保存原值,不解析、不推断结构。

---

## 6. Identity / native keys and cardinality(身份、native key 与基数)

### 6.1 内部 ID vs 平台 native ID

- 内部 UUID 是唯一权威身份;native key(`native_account_key`、`native_store_key`)
  是**平台侧标识的不透明镜像**,仅用于未来对账与去重锚点;
- native key 可缺失(NULL=尚未录入/未知),录入后原样保存(保留大小写与格式),
  **不做任何格式猜测或规范化**——其结构语义属 OQ-102/平台 WP 证据;
- 录入与变更均审计;native key 变更需要 `reason`(§16)。

### 6.2 唯一性作用域与重复检测

| 对象 | 唯一约束 | 说明 |
| --- | --- | --- |
| organization | `code` 全局唯一 | |
| legal_entity | (`organization_id`,`code`) | |
| marketplace_account | (`organization_id`,`code`);(`platform_code`,`native_account_key`)部分唯一(native 非空且 status≠RETIRED) | 同一平台上同一 native 账户不得被重复登记为两个活跃账户 |
| store | (`organization_id`,`code`);(`marketplace_account_id`,`native_store_key`)部分唯一(同上条件) | |
| warehouse | (`organization_id`,`code`) | |
| service_account | (`organization_id`,`code`) | |
| credential_metadata | (`organization_id`,`code`);`secret_reference` 全局部分唯一(status≠REVOKED) | 同一 Secret 引用不得同时挂在两条活跃 Credential 元数据上,防止别名混淆;**注意:不存在任何(account, purpose)层面的唯一/防重叠约束——新旧凭据重叠是轮换要求(§11.4)** |
| credential_store_scope | (`credential_id`,`store_id`)部分唯一(status=ACTIVE) | 同一凭据对同一 Store 只有一条活跃范围行 |
| platform_capability | (`platform_code`,`capability_code`) | |
| platform_endpoint | (`platform_code`,`endpoint_code`,`api_version`) | |
| capability_subject_status | (`capability_id`,`marketplace_account_id`)部分唯一(account 非空);(`capability_id`,`store_id`)部分唯一(store 非空) | 每能力每主体一条状态行 |
| platform_permission_requirement | (`platform_code`,`requirement_kind`,`external_code`,目标列)部分唯一 | 外部要求证据行不重复登记 |
| service_account_allowed_source | (`service_account_id`,`cidr`)部分唯一(status=ACTIVE) | 撤回后允许重新登记为新行 |
| feature_flag | (`flag_code`,`scope_key`)(scope_key 为 STORED 生成列,§13.2) | |
| service_account_scope_grant | (`service_account_id`,`permission_code`,scope 目标)部分唯一(status=ACTIVE) | 同一主体对同一资源同一权限只有一条活跃授予 |

重复创建(唯一冲突)返回 `DUPLICATE_IDENTITY`,响应携带既存实体 id ——
创建重试因此天然幂等安全(§16.5)。

### 6.3 基数灵活性(OQ-101 约束的落点)

```text
Organization 1 ── N Legal Entity          (当前实际 1,不设上限)
Legal Entity 1 ── N Marketplace Account   (平台数与账户数不设限)
Marketplace Account 1 ── N Store          (不假设 1:1)
Legal Entity 1 ── N Warehouse             (Warehouse 属 Legal Entity,不属 Store)
Store N ── M Warehouse                    (经 store_warehouse_link,按模式+区间)
```

未来经核验的平台约束(例如某平台账户↔店铺确为 1:1)以**平台扩展层**表达:
平台 WP 在 `platform` schema 增加平台特定约束表或校验规则,并以 Registry 证据
(§12.6)为出处;**不回写、不收窄通用模型**。

### 6.4 native key 复用与退役

- 实体 RETIRED 后其 native key 退出部分唯一索引作用域,允许同一 native key 在
  新实体上重新登记(平台侧账户迁移/重开场景),历史归属由审计与旧实体行保留;
- 同一 native key 在**两个活跃实体**上出现被数据库拒绝(§6.2),错误
  `DUPLICATE_IDENTITY` 并审计尝试;
- 不做跨平台 native key 全局唯一假设(平台命名空间彼此独立)。

---

## 7. Effective dating, timezone and currency(生效期、时区与币种)

### 7.1 生效期语义

- 适用对象:`store_warehouse_link`、`store_fulfillment_declaration`、
  `service_account_scope_grant`(§9.6)。**`credential_metadata` 不属于本族**:凭据的时间模型是 `effective_from` + `expires_at` + 状态机(§11.4),
  且**明确要求**新旧凭据可重叠——防重叠约束对凭据是错误语义,本族的
  Exclusion Constraint 一概不施加于凭据表;
- 统一半开区间 `[effective_from, effective_to)`,`timestamptz`(UTC);
  `effective_to = NULL` 表示开放区间;
- `effective_from < effective_to` 由 CHECK 约束保证;
- "当前生效" = `status=ACTIVE AND effective_from <= now < COALESCE(effective_to, ∞)`,
  由查询层统一实现,不在各处复制。

### 7.2 重叠防止(数据库层)

对每张生效期表,以 `btree_gist`(V-101)建 Exclusion Constraint(示意):

```sql
ALTER TABLE core.store_warehouse_link ADD CONSTRAINT store_warehouse_link_no_overlap
  EXCLUDE USING gist (
    store_id WITH =, warehouse_id WITH =, fulfillment_mode_code WITH =,
    tstzrange(effective_from, effective_to, '[)') WITH &&
  ) WHERE (status = 'ACTIVE');
```

应用层在写入前做同等预检以给出友好错误 `EFFECTIVE_RANGE_OVERLAP`;并发竞争窗口
由数据库约束兜底(应用把约束违例翻译为同一错误码)。**重叠防止因此不是
application-only 不变量。**

### 7.3 冲突/并发与更正

- 区间修改(提前结束、顺延)是普通带版本更新;与重叠约束冲突 → 409;
- 历史更正走 **forward correction**:修正字段值 + 强制 `reason` + 审计记录旧值/新值
  (§14.4);不重写审计、不物理删除;录错的关联以 `CANCELLED` 终态标记并保留行;
- 元数据历史版本保留机制 = 审计事件中的字段级 old/new 快照(可按 entity 回放),
  本 WP 不引入双时态表——对低频维护型元数据,审计回放已满足 IAM-007/ADM-001
  的可检索历史要求,双时态属过度设计(显式取舍)。

### 7.4 时区与币种

| 项 | 决策 |
| --- | --- |
| 存储 | 一切时刻 `timestamptz` UTC;展示层(未来 console)负责本地化,后端不做本地时区换算 |
| 时区字段 | IANA zone id 文本(如 `Asia/Novosibirsk`);写入时经 `java.time.ZoneId.of` + `ZoneId.getAvailableZoneIds()` 校验;非法 → `INVALID_TIMEZONE`;DB 侧 CHECK 只保格式(长度/字符集),权威校验在应用层(JDK tzdata 随运行时更新,DB 无法维护同等清单——这是唯一合理的分层,配 TC-OM-112 补偿) |
| 币种字段 | ISO-4217 alpha-3;写入时经 `java.util.Currency.getInstance` 校验;非法 → `INVALID_CURRENCY`;DB CHECK `~ '^[A-Z]{3}$'` |
| 金额 | 本 WP 无金额字段;未来金额一律 Decimal + 显式币种(Baseline 硬规则,记录于此以防实施漂移) |
| 未知 | Store/Warehouse 时区、Store 币种允许 NULL(=未录入),查询显式呈现 UNKNOWN;不默认 RUB、不默认莫斯科时区(不猜测业务事实) |

---

## 8. Generic fulfillment-family metadata(通用履约家族元数据)

### 8.1 参考表 `core.fulfillment_mode`(确定性种子)

| code | 含义 | 说明 |
| --- | --- | --- |
| `MARKETPLACE_FULFILLED` | 平台履约:订单由平台方仓储/物流完成 | 通用语义,不绑定任何平台的 FBO 等命名 |
| `SELLER_FULFILLED` | 卖家履约:订单由卖家自有运营节点完成 | 同上,不绑定 FBS 等命名 |
| `UNKNOWN` | 实际模式未知 | fail-closed 占位;不被强制成前两者 |

**不声明任何"当前 Ozon/WB 履约模式已核验"**;平台特定命名(FBO/FBS/rFBS/…)与其
到通用家族的映射属 WP-P0-005/006 的证据工作,落点为未来 `platform` schema 的
映射表(版本化、带出处),**不修改本参考表语义**。参考表允许后续 WP 以增量迁移
追加新通用模式(如混合/跨仓),`code` 永不复用。

### 8.2 两个attachment 点与共存语义

- **Store 层声明**(`store_fulfillment_declaration`):店铺以何种模式运营。
  一个 Store 可同时声明多个模式(各自区间);Marketplace-fulfilled 的店铺可以
  没有任何 Warehouse 关联——这正是 Warehouse 不作 Store 子节点的原因之一;
- **Store↔Warehouse 关联层**(`store_warehouse_link`):某仓库在某区间内以某模式
  服务某店铺(卖家履约发货、或作为平台履约的供货节点)。同一 (store,warehouse)
  允许多条不同模式、不同区间的关联并存;
- 两层各自区间防重叠(§7.2),互不强制一致(声明了 SELLER_FULFILLED 但尚未建立
  仓库关联是合法中间状态,数据质量 WP 后续可发告警,不在本 WP 伪造完整性);
- Warehouse 本身只建模**卖家(Legal Entity)拥有的**运营节点;平台方仓库不在本 WP
  建模(属平台事实,UNKNOWN 直至平台 WP 核验后另行扩展)。

---

## 9. Authorization metadata(授权元数据——仅元数据/策略基础,非运行时执行)

### 9.1 定位声明(必须原样保留到实施文档)

> 本节全部内容是**策略元数据**。元数据的存在**不是**运行时访问控制的证明。
> 在 OQ-005 关闭并由后续 WP 实现运行时 IAM 之前,系统不存在用户认证与授权执行。
> 本设计不选择身份提供商,不实现 RBAC/ABAC 决策点。

### 9.2 权限分类 `iam.permission_kind`(确定性种子,IAM-004)

| code | 含义 |
| --- | --- |
| `READ` | 读取业务数据 |
| `WRITE` | 变更业务/平台数据(未来受 Controlled Write Gate 全链约束) |
| `FINANCE` | 财务数据与结算可见性 |
| `ADS` | 广告数据与操作 |
| `CREDENTIAL_ADMIN` | Credential 元数据管理 |

五者**彼此独立、互不蕴含**:`WRITE` 不隐含 `READ`,任何权限不隐含
`CREDENTIAL_ADMIN`。本 WP 不定义角色捆绑(角色属运行时 IAM WP);没有任何
"全权限"聚合值。参考表允许后续增量追加,`code` 永不复用。

### 9.3 资源范围类型(scope taxonomy)

```text
ORGANIZATION | LEGAL_ENTITY | MARKETPLACE_ACCOUNT | STORE | WAREHOUSE
```

范围 = (scope 类型, 具体资源 UUID)。范围**不级联展开**:授予 ORGANIZATION 范围
表示"以组织为资源边界"这一显式声明,未来运行时 IAM 如何解释层级包含关系是
执行层决策——元数据层只忠实记录声明,不预演执行语义(防止元数据被误读为已
生效的访问控制)。

### 9.4 范围附着点

- 五类实体表的 UUID 即附着点(IAM-001);
- 本 WP 的授予主体**仅** Service Account(`iam.service_account_scope_grant`);
  scope 模型本身与主体类型无关,未来用户主体由运行时 IAM WP 以增量表接入
  (设计边界见 §24 IAM-001 行)。

### 9.5 `iam.service_account_scope_grant`

`id`、`organization_id`、`service_account_id`(FK)、`permission_code`(FK →
`permission_kind`)、恰好一个非空的资源列:`organization_ref_id` /
`legal_entity_ref_id` / `marketplace_account_ref_id` / `store_ref_id` /
`warehouse_ref_id`(CHECK `num_nonnulls(...)=1`,各列复合 FK 保证组织一致,§17.2)、
`effective_from`、`effective_to`、`status`(ACTIVE/REVOKED)、`reason`、时间戳、`version`。

### 9.6 deny / default 语义与防放大不变量(I-04)

- **deny-by-default**:无授予即无权限;不存在通配資源、通配权限;
- 授予只能是**显式正向**记录;无否定记录(否定=删除授予=REVOKED);
- 授予的组织必须同时等于 Service Account 的组织与资源的组织(数据库复合 FK +
  应用校验双重保证)——跨组织授予不可表示;
- 授予变更(新授/撤销)一律审计并要求 `reason`;
- **无隐式放大**:创建/修改授予的维护操作不能因请求方持有某授予而扩展另一授予
  (本阶段维护面无认证,该不变量表现为:任何授予的产生都只能来自显式维护命令 +
  操作者归因 + 审计,不存在任何自动派生授予的代码路径;运行时 IAM WP 继承该
  不变量并加上执行语义);
- 策略版本化:授予行本身带生效区间与版本;同一(主体,权限,资源)的历史授予以
  REVOKED 行保留,形成可回放的策略历史——不需要独立策略版本表(显式取舍)。

### 9.7 未来运行时 IAM 消费边界

运行时 IAM WP 将把本节元数据作为**策略源**读取(经 `identityaccess` 公共查询 API,
不直读表),并自行负责:认证、会话、决策点、缓存失效、行级过滤。本设计保证
策略源的可查询性(§16 查询契约)与审计完整性,不承诺任何执行行为。

---

## 10. Service Account metadata(服务账户元数据)

### 10.1 `iam.service_account`

| 字段 | 语义 |
| --- | --- |
| `id`、`organization_id`、`code`、`display_name` | 稳定身份与组织归属 |
| `purpose` | NOT NULL 文本:该账户存在的单一明确用途(如 "ozon-order-ingestion") |
| `owner_label` | NOT NULL:责任人/责任组标签(内部标识,不含个人联系 PII) |
| `expires_at` | NOT NULL `timestamptz`:**强制到期**;创建时必须显式给出(无"永不过期"默认——需要长期账户时操作者显式选择远期日期并被审计) |
| `status` | ACTIVE / DISABLED / REVOKED(§18.4) |
| `disabled_reason` | DISABLED/REVOKED 时 NOT NULL |
| `last_used_at` | nullable;本 WP 恒为 NULL,未来运行时集成回填;查询层呈现 UNKNOWN |
| 时间戳、`version` | 同通用决策 |

### 10.2 `iam.service_account_allowed_source`

`id`、`service_account_id`(FK)、`cidr`(文本,应用侧以 IPv4/IPv6 CIDR 语法校验)、
`note`、`status`(**ACTIVE / WITHDRAWN**)、`reason`(WITHDRAWN 时 NOT NULL)、
时间戳、`version`。生命周期:`ACTIVE → WITHDRAWN`(终态)——与全库无 DELETE
不变量一致(I-12);撤回后同一 `cidr` 可重新登记为新行(部分唯一约束
`WHERE status='ACTIVE'`,§6.2);状态机收录于 §18.4a,API 为
`POST /{id}/allowed-sources/{sid}/status`。

**声明性网络元数据**:记录允许来源;运行时网络执行属未来 IAM/Credential 运行时
WP。零条 **ACTIVE** 行 = 来源未声明 = 未来执行层必须视为 deny(记录于消费契约,
防止"空=放行"误读)。

### 10.3 fail-closed 评估(I-05)

`AccessMetadataDirectory.evaluate(id)` 返回单一枚举:

```text
ACTIVE     status=ACTIVE 且 now < expires_at
EXPIRED    status=ACTIVE 且 now >= expires_at   (派生态,不落库,不自动改写 status)
DISABLED   status=DISABLED
REVOKED    status=REVOKED
UNKNOWN    id 不存在或数据不可读
```

**除 `ACTIVE` 外一律拒绝使用**;EXPIRED 不被静默改写为 DISABLED(状态列忠实保留
操作者意图,派生态由时钟计算——审计历史因此不含系统自动状态改写噪音)。
消费方(WP-P0-003 Job 定义)必须在每次使用点调用该评估,评估拒绝计入
观测信号(§20)。

### 10.4 显式排除

Token 签发、登录/会话、真实 Secret 检索、运行时网络执行不在本 WP。
WP-P0-003 的证明路径:Job 元数据强制 `service_account_id` NOT NULL 引用 +
本目录评估 + 架构测试禁止 Job 路径引用任何"个人用户"概念(该概念本阶段
根本不存在于代码库,测试断言其持续不存在)。

---

## 11. Credential metadata / Secret-reference contract(凭据元数据与 Secret 引用契约)

### 11.1 三层语义分离与数据模型

三个易被混同的概念各有独立结构,互不冒充:

```text
(1) 内部授权     iam.permission_kind(READ/WRITE/FINANCE/ADS/CREDENTIAL_ADMIN)
                 ——"谁可以在 MarketOps 内做什么";CREDENTIAL_ADMIN 是管理凭据
                 元数据的内部权限,与任何平台凭据用途无关。
(2) 凭据用途     platform.credential_purpose(本节)——"这条平台凭据被创建来
                 干什么",取值直接来自 Baseline 平台账号治理的凭据分离要求。
(3) 平台侧要求   platform.platform_permission_requirement(§12.5a)——平台自己的
                 API 角色/scope/订阅要求,OQ-102 证据,本 WP 零数据行。
```

**`platform.credential_purpose`**(参考表,确定性种子;来源为 Baseline
平台账号治理原文"Read、Finance、Inventory Write、Price Write、Ads Write
Credential 分离"——内部治理分类,非平台 API 事实):

| code | 含义 |
| --- | --- |
| `READ` | 读取类平台凭据 |
| `FINANCE` | 财务/结算读取类凭据 |
| `INVENTORY_WRITE` | 库存写入类凭据(未来 Controlled Write Gate 约束) |
| `PRICE_WRITE` | 价格写入类凭据(同上) |
| `ADS_WRITE` | 广告写入类凭据(同上) |

后续经增量迁移可追加;`code` 永不复用;**不含 `CREDENTIAL_ADMIN`**。
一条凭据恰一个用途(HR-06 的凭据按用途分离要求);同一账户多个用途 = 多条
凭据、各自独立 `secret_reference`。

**`platform.credential_metadata`**(仅非密元数据):

| 字段 | 语义 |
| --- | --- |
| `id`、`organization_id`、`code`、`display_name` | 身份 |
| `marketplace_account_id` | NOT NULL FK(复合 FK 保组织一致)——凭据锚定在账户级 |
| `purpose_code` | NOT NULL FK → `platform.credential_purpose` |
| `scope_mode` | NOT NULL,`ACCOUNT` / `STORE_SET`(CHECK):**显式范围声明,创建时给定**。`ACCOUNT` = 整账户范围;`STORE_SET` = 仅限其活跃 Store 范围行所列集合。范围语义只由本列决定,**永不由子表行数推断**(§11.1a) |
| `secret_reference` | NOT NULL,不透明引用,格式 §11.2;**永不是 Secret 值** |
| `effective_from` | NOT NULL:凭据可开始使用的时刻(元数据声明) |
| `expires_at` | NOT NULL:外部凭据硬到期(操作者登记;Baseline 要求登记到期)。**无 `effective_to` 字段**——使用终止由状态迁移表达,不由业务日期表达(§11.4 的唯一时间模型) |
| `replaces_credential_id` | nullable 自 FK:本凭据替换哪条旧凭据(轮换链,§11.4);应用校验同账户、同用途、目标非 REVOKED |
| `status` | ACTIVE / DISABLED / REVOKED(§18.5) |
| `custodian_label` | NOT NULL 保管责任人/组标签 |
| `last_used_at` | nullable;本 WP 恒 NULL(同 §10.1) |
| `verification_state` | 固定 `UNVERIFIED`(本 WP 无连通性验证路径;CHECK 禁止 `VERIFIED`——该值随未来 Credential 运行时 WP 的迁移放开) |
| 时间戳、`version` | 通用 |

### 11.1a 凭据范围契约(显式、fail-closed、数据库可执行)

**`platform.credential_store_scope`**(Store 范围行;仅对 `STORE_SET` 凭据有意义):

`id`、`credential_id`、`marketplace_account_id`(**本表实列**,复合 FK 的载体)、
`store_id`、`status`(ACTIVE/WITHDRAWN)、`reason`(WITHDRAWN 时 NOT NULL)、
时间戳、`version`。

数据库级跨账户不可表示(两条复合 FK 钉住同一账户键):

```sql
-- 父表辅助唯一键
ALTER TABLE platform.credential_metadata
  ADD CONSTRAINT credential_metadata_id_account_uk UNIQUE (id, marketplace_account_id);
-- core.store 已有 UNIQUE (id, marketplace_account_id)(§17.2)

ALTER TABLE platform.credential_store_scope
  ADD CONSTRAINT credential_store_scope_credential_fk
    FOREIGN KEY (credential_id, marketplace_account_id)
    REFERENCES platform.credential_metadata (id, marketplace_account_id),
  ADD CONSTRAINT credential_store_scope_store_fk
    FOREIGN KEY (store_id, marketplace_account_id)
    REFERENCES core.store (id, marketplace_account_id);
```

绕过应用服务的直接 SQL 也无法把他账户的 Store 挂到凭据上(负向测试 TC-MI-114);
组织一致性由账户键传递保证(凭据与 Store 的账户各自已被组织复合 FK 钉住)。

**范围语义与 fail-closed 规则:**

| 规则 | 内容 |
| --- | --- |
| 显式声明 | 范围只由 `scope_mode` 决定。`ACCOUNT` 凭据不得拥有 ACTIVE 范围行(追加即拒);`STORE_SET` 凭据的可用范围 = 其 ACTIVE 范围行集合 |
| 空集 fail closed | `STORE_SET` 且 ACTIVE 范围行为零 ⇒ 凭据**不可用**(派生态 `scopeUsability = NO_ACTIVE_STORE_SCOPE`,一切消费评估拒绝并观测)——**永不**回落为整账户范围 |
| 原子创建 | 创建命令一次携带 `scopeMode`;`STORE_SET` 时必须同时携带非空 `storeIds`,凭据行与初始范围行在同一事务提交——不存在"先账户可见、后补 Store"的中间态(即使被并发读到,空集规则也已 fail closed,双重保护) |
| 撤回最后一行 | 允许;结果是空集 fail-closed 不可用态,`scope_mode` **不变**、范围**不扩大**;操作与结果均审计并产生 WARN 观测信号(§20)。恢复 = 追加新范围行,或走显式模式变更 |
| 显式扩权 | `STORE_SET → ACCOUNT` 是独立命令(`reason` 必填、独立审计),且要求全部范围行已 WITHDRAWN(先显式清场,再显式扩权;否则 `INVALID_STATE_TRANSITION`)。扩权永不是删行的副作用 |
| 显式收窄 | `ACCOUNT → STORE_SET` 是独立命令,必须同命令携带非空初始 `storeIds`(同事务生效,无空集中间态) |

一条凭据一个 `secret_reference`,任意多 Store——多 Store 不复制元数据行,
`secret_reference` 唯一约束(§6.2)与范围表达无冲突。范围行与 `scope_mode`
的联动("ACCOUNT 无活跃范围行"“STORE_SET 空集不可用")是跨行/跨表条件,
数据库 CHECK 无法表达,归入 §17.3 应用级不变量清单,由 TC-MI-112/114 全矩阵
与并发测试补偿。

Capability 关联:凭据实际可用于哪些平台能力属 OQ-102 证据,未来由
`capability_subject_status`(§12.5)与 `platform_permission_requirement`(§12.5a)
承载,不在本表猜测。

### 11.2 Secret 引用格式(不透明、可校验、非密)

```text
^secret-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,4}$
```

- 固定 scheme `secret-ref://` + 命名空间路径(如
  `secret-ref://marketops/ozon/account-a/read-api`),总长 ≤ 320;
- 引用是**名字**,不是值:校验拒绝空白、拒绝 `=`、拒绝任何不匹配模式的输入;
- **Secret 疑似材料拒绝**(纵深防御,I-06):对 `secret_reference` 及全部自由文本
  字段(display_name/purpose/note/reason),应用校验拒绝含以下特征的值并返回
  `SECRET_MATERIAL_SUSPECTED`(不回显原值,审计只记字段名与规则名):
  长度 ≥ 64 的连续 Base64/hex 样式 token、`Bearer ` 前缀、PEM 头
  (`-----BEGIN`)、`password=`/`token=`/`secret=` 键值样式;
- 该启发式**只用于拒绝写入**,永不用于"检测后脱敏放行"(不产生截断存储)。

### 11.3 明文 Secret 禁止面(逐面枚举 + 强制机制)

| 面 | 机制 |
| --- | --- |
| 数据库 | 表上不存在任何可容纳 Secret 的列语义;写入面经 §11.2 拒绝疑似材料 |
| Java 序列化 | 元数据 record 不含 Secret 字段;`toString()` 为字段白名单生成(record 默认即白名单);无 `Serializable` 旁路缓存 |
| REST/API 载荷 | 请求 DTO 严格绑定(未知字段拒绝,§16.3):`token`/`password`/`secret` 等未知键触发 `VALIDATION_FAILED` 且**不回显内容**;响应 DTO 字段白名单测试(TC-MI-104) |
| 前端/UI | 本 WP 无凭据 UI;契约写明未来 console 只显示元数据(ADM-005 方向) |
| 日志 | 沿用既有安全日志契约(固定事件+错误码+correlationId+异常类名,丢弃消息与堆栈);admin 路径**永不记录请求体**;log-capture 测试断言(TC-OB-103) |
| 异常 | 校验异常不携带原始输入值;`GlobalExceptionHandler` 既有行为保持 |
| Tracing | 本阶段无 tracing 导出;契约预留:未来接入时 admin 路径请求体属禁采面 |
| 迁移 | V0002–V0006 零数据值写入(仅结构 + 枚举种子);governance Secret 扫描覆盖 |
| Fixtures | 测试夹具只使用 `secret-ref://test/...` 形态引用;fixture 命名显式 test-only |
| 测试失败输出 | 断言消息引用字段名/规则名,不嵌入被拒原值(实施 review checklist 项) |
| 证据 | 沿用 WP-P0-001 证据脱敏九类清单;证据不含任何引用之外的凭据材料 |
| 文档/示例 | 本文档及未来 runbook 示例一律 `secret-ref://` 形态,无任何示例 Token |

### 11.4 唯一时间模型与重叠轮换

**时间模型(凭据的全部时间语义,别无其他):**

```text
可用窗口 = [effective_from, expires_at)  ∩  status = ACTIVE
EXPIRED  = 派生态(now >= expires_at),不落库、不自动改写 status
使用终止 = 状态迁移(DISABLED / REVOKED),不是业务日期;无 effective_to
```

**重叠是轮换的要求,不是异常(Baseline:新旧 Credential 重叠验证后切换):**

- 同一 `(marketplace_account_id, purpose_code)`(乃至同一 Store 范围)下**允许
  多条 ACTIVE 凭据并存**;凭据表上**不存在任何防重叠 Exclusion Constraint 或
  唯一约束**来限制并存(§6.2 显式注记)——唯一性只作用于 `code` 与
  `secret_reference`;
- 轮换以 `replaces_credential_id` 显式建链:
  1. 登记新凭据(新 `secret_reference`),`replaces_credential_id` 指向旧凭据
     ——从此刻起新旧并存(重叠验证窗口);
  2. 外部验证/切换完成后,操作者对旧凭据执行 `DISABLE`(可回退)或
     `REVOKE`(终态)——重叠窗口结束;
  3. 全程两条独立元数据行、两个独立引用,审计完整;
- **无独立轮换状态列**:轮换进行中 = "存在引用我的非 REVOKED 后继"这一派生
  事实,由查询层输出(`rotationStatus: STABLE | BEING_REPLACED`)——不维护与
  链平行的第二套状态,单一事实源;
- 链约束(应用层 + 测试补偿):后继与被替换者必须同账户、同用途;
  `replaces_credential_id` 目标必须存在且非 REVOKED(登记时点);一条旧凭据
  允许多个候选后继(重试/纠错场景),审计与查询如实呈现,不强行唯一化。

到期告警窗口(30/14/7 天,Baseline)属未来观测 WP 的调度职责;本 WP 提供
`expires_at` 与轮换链的可查询性。全部状态迁移审计 + reason 强制。

### 11.5 到期/失效的 fail-closed 消费与确定性选择契约

任何未来消费方在使用点必须:

1. 过滤候选:`status=ACTIVE ∧ now ∈ [effective_from, expires_at) ∧
   verification_state 满足其用途要求 ∧ 范围匹配`——范围匹配按 §11.1a:
   `ACCOUNT` 凭据匹配其账户内任意目标;`STORE_SET` 凭据仅匹配其 ACTIVE 范围行
   所列 Store,**空集一律不匹配**(`NO_ACTIVE_STORE_SCOPE` fail closed);
2. 候选为空 ⇒ **拒绝**(fail closed)并观测;
3. 候选多于一条且互为轮换链上的前驱/后继 ⇒ **确定性选择链上最新后继**;
4. 候选多于一条且无链关系(异常的模糊并存)⇒ **拒绝**(fail closed)并观测
   ——绝不任意挑选。

本 WP 内唯一消费方是维护/查询面自身(展示派生态 EXPIRED 与 rotationStatus);
上述契约为未来 Credential 运行时 WP 的强制消费规则,记录于此以冻结语义。

### 11.6 未来 Secret Manager 集成边界(仅边界,不落代码)

未来 Secret 解析的边界形状在此**以设计固化**:OQ-006 关闭后的 Credential 运行时
WP 在 `marketplaceintegration.internal.port` 引入
`SecretResolutionPort.resolve(secretReference)`,其实现放在
`marketplaceintegration.adapter.<secret-manager>`,返回不落地、不记日志的运行时
句柄;Domain 与公共 API 永不出现 Secret 值类型。**本 WP 不创建该接口文件**——
一个没有任何调用方与实现的生产接口是脚手架死代码,违反 Compromise Retirement
Check;边界由本节 + §24 INT-003 行 + 架构规则共同冻结即可。**不选择供应商。**

---

## 12. Endpoint / Capability Registry(端点与能力注册表)

### 12.1 定位

平台中立的 Registry **结构**,今天只承载 `UNKNOWN`/`UNVERIFIED` 语义,未来由
WP-P0-005/006 以官方一手证据填充 `VERIFIED` 事实。**本设计不录入任何平台
capability/endpoint 事实行**(平台名参考行 OZON/WILDBERRIES 属 Baseline 既定
产品事实,由 §5.2 的 `core.marketplace_platform` 随 V0004 种子)。

### 12.2 平台身份参考

平台身份参考表归属 `organizationaccount`(`core.marketplace_platform`,§5.2):Registry 各表经 FK **单向**引用其 `code`,`marketplaceintegration`
经 `OrganizationDirectory` 的平台参考查询解析平台码——本模块不拥有平台身份。

### 12.3 `platform.platform_capability`(逻辑能力)

| 字段 | 语义 |
| --- | --- |
| `id`、`platform_code`(FK → `core.marketplace_platform`)、`capability_code` | 平台内唯一能力码(如 `product.read`——**命名空间自定,不是平台 API 名**) |
| `display_name`、`description` | |
| `applies_to` | `MARKETPLACE_ACCOUNT` / `STORE` / `UNKNOWN`(能力作用粒度) |
| `read_write_class` | `READ` / `WRITE`(CHECK) |
| `subscription_required` | `YES` / `NO` / `UNKNOWN`(默认 UNKNOWN) |
| `verification_state` | `UNKNOWN` / `UNVERIFIED` / `VERIFIED`(§12.6;本 WP 无 VERIFIED 路径) |
| `last_verified_at`、`evidence_ref`、`verified_source_title` | 出处三元组;CHECK:`VERIFIED` ⇒ 三者全非空 |
| `owner_label`、`contract_test_status`(`NOT_IMPLEMENTED`/`FAILING`/`PASSING`,默认 NOT_IMPLEMENTED) | |
| `deprecated_at`、`replacement_capability_id`(自 FK,**同平台复合 FK**,§17.2——替代目标不能跨平台) | 废弃/替代元数据 |
| `status`(ACTIVE/RETIRED)、时间戳、`version` | |

### 12.4 `platform.platform_endpoint`(物理端点/版本)

`id`、`platform_code`、`endpoint_code`、`api_version`、`http_method`(nullable)、
`path_template`(nullable,不猜测——录入时才有)、`capability_id`(nullable,
**同平台复合 FK**,§17.2)、
`read_write_class`、`pagination_model`(`CURSOR`/`OFFSET`/`PAGE`/`DATE_WINDOW`/
`NONE`/`UNKNOWN`,默认 UNKNOWN)、`rate_limit_note`(nullable 结构化文本)、
`rate_limit_per_minute`(nullable int)、`quota_note`(nullable)、
`idempotency_support`(`YES`/`NO`/`UNKNOWN`)、`late_data_behavior`(nullable 文本,
NULL=UNKNOWN)、`freshness_expectation`(nullable)、`business_key_note`(nullable)、
`schema_version`(nullable)、`deprecated_at`、`replacement_endpoint_id`(自 FK,
**同平台复合 FK**,§17.2)、
验证/出处四联(同 §12.3 + CHECK)、`owner_label`、`contract_test_status`、
`status`、时间戳、`version`。

速率/配额/分页/迟到语义等**全部默认 UNKNOWN/NULL**,只能随核验证据被填充。

### 12.5 `platform.capability_subject_status`(主体柔性能力矩阵;OQ-102 的落点结构)

`id`、`organization_id`、`platform_code`(NOT NULL:本行所属平台命名空间)、
`capability_id`(**同平台复合 FK**,§17.2)、**主体:`marketplace_account_id` /
`store_id` 恰一非空**(CHECK `num_nonnulls(...)=1`;组织复合 FK 保组织一致;
账户主体另经 `(marketplace_account_id, platform_code)` 复合 FK 钉住平台——
Ozon 账户不能挂 WB 能力状态)、`availability`(`UNKNOWN`/`UNAVAILABLE`/
`AVAILABLE`,默认 UNKNOWN)、验证/出处四联(CHECK:`availability≠UNKNOWN` ⇒
出处非空)、时间戳、`version`。

- 主体种类与能力 `applies_to` 的一致性(`applies_to=MARKETPLACE_ACCOUNT` ⇒ 主体
  必须是账户;`STORE` ⇒ 主体必须是 Store;`UNKNOWN` ⇒ 两者皆可登记但
  `availability` 不得离开 `UNKNOWN`)为应用级校验——跨表条件超出 CHECK 表达力,
  以 TC-MI-121 全矩阵测试补偿(§17.3 application-only 清单同步收录);
- 本 WP 仅建结构,**零数据行**;Store 级与账户级可用性证据均由 WP-P0-005/006
  以官方证据填充。

### 12.5a `platform.platform_permission_requirement`(外部平台权限要求证据结构)

INT-002 要求 Registry 能保存平台**自己的**权限/角色/订阅要求。本表是该证据的
结构化落点(本 WP 零数据行):

`id`、`platform_code`(FK → `core.marketplace_platform`)、目标:`capability_id` /
`endpoint_id` 恰一非空(`num_nonnulls=1`;两列均为**同平台复合 FK**,§17.2——
证据行声明的平台与目标对象的平台在数据库层强制一致)、`requirement_kind`(`API_ROLE` /
`OAUTH_SCOPE` / `SUBSCRIPTION` / `PLAN` / `OTHER` / `UNKNOWN`)、`external_code`
(平台自有标识,**不透明原样保存,不猜测**)、`description`、`verification_state`
(`UNKNOWN`/`UNVERIFIED`/`VERIFIED` + 出处 CHECK,同 §12.3)、
`last_verified_at`、`evidence_ref`、`verified_source_title`、`status`、时间戳、`version`。

与内部授权的关系:Registry **不持久化任何 Capability → 内部权限映射**。内部
MarketOps 权限分类的唯一权威在 `iam.permission_kind`(§9.2);本表是平台自身
授权语言的证据镜像。本 WP 不存在需要 Capability→IAM 映射的运行时消费方,
`platform.*` 持久层因此不引用 `iam.*` 的任何对象(§3.2/§17.2 的单向依赖图);
未来运行时 IAM/策略 WP 在出现真实执行消费方时,再以显式映射结构接入并自有
其执行语义。

### 12.6 验证状态机与出处

```text
UNKNOWN ──(登记为待核验)──▶ UNVERIFIED ──(官方一手证据)──▶ VERIFIED
VERIFIED ──(证据过期/平台变更/复核失败)──▶ UNVERIFIED     (降级必须带 reason)
任何状态 ──(能力退役)──▶ RETIRED(status 列,verification_state 保留末值)
```

- 每次 `verification_state` / `availability` 迁移必须同步 INSERT 一行
  `platform.capability_verification_event`(append-only:`id`、目标列
  `capability_id` / `endpoint_id` / `capability_subject_status_id` /
  `platform_permission_requirement_id` **恰一非空**(`num_nonnulls=1`)、
  `from_state`、`to_state`、`evidence_ref`、`source_title`、`verified_at`、`actor`、
  `reason`、`occurred_at`、`correlation_id`)——验证历史不可改写;
- **本 WP 内不存在任何把状态置为 `VERIFIED` 的代码路径**:维护 API 对
  `VERIFIED` 目标值直接拒绝(`CAPABILITY_VERIFICATION_NOT_SUPPORTED`,见 §16.4;
  该拒绝路径由平台 WP 以证据链路替换,替换时同步移除拒绝并更新测试——
  显式清理触发器,防止妥协残留);
- **fail-closed 消费(I-07)**:`CapabilityDirectory.isUsable()` 仅当
  `status=ACTIVE ∧ verification_state=VERIFIED ∧ 未废弃` 才可能为真——在本 WP 的
  数据条件下**恒为假**,并且每次否定评估产生观测信号(§20)。UNKNOWN/UNVERIFIED
  **永不启用任何行为**。

---

## 13. Feature Flag / capability safety model(功能开关与能力安全模型,ADM-002 部分闭合)

### 13.1 全局生产写门(外部权威,元数据不可覆盖,I-08)

- 治理事实源保持:`CURRENT_STATE.md` 的 `production_write_enabled: false` +
  `validate_production_readiness.py`;
- 运行时映射:`shared` 新增 `ProductionWritePolicy`,绑定配置
  `marketops.production-writes.enabled`(基线 `application.yaml` 显式 `false`),
  以 Bean Validation `@AssertFalse` 校验——**任何环境把它配成 true 都会导致
  应用启动失败**(本阶段不存在 Controlled Write Capability Gate,`true` 是非法
  配置;未来 Gate WP 以显式迁移替换该校验)。这把"Flag 覆盖不了全局门"从纪律
  变成机器事实;
- `platform.feature_flag` 表**没有**任何被运行时写路径读取的语义(本阶段不存在
  写路径);它是纯管理元数据。

### 13.2 `platform.feature_flag`

| 字段 | 语义 |
| --- | --- |
| `id`、`flag_code` | 开关标识 |
| `flag_kind` | `OPERATIONAL`(普通功能开关)/ `WRITE_CAPABILITY`(平台写能力开关) |
| `scope_kind` | `GLOBAL` / `PLATFORM` / `MARKETPLACE_ACCOUNT` / `STORE` / `CAPABILITY` |
| scope 引用列 | `platform_code` / `marketplace_account_id` / `store_id` / `capability_id`(nullable;CHECK 矩阵:scope_kind 决定恰好哪一列非空,GLOBAL 全空) |
| `scope_key` | STORED 生成列:`scope_kind` 与引用列的规范拼接;`(flag_code, scope_key)` 唯一 |
| `state` | `DISABLED` / `ENABLED`;**默认 DISABLED** |
| `description`、`reason`(最近一次状态变更理由) | |
| `status`(ACTIVE/RETIRED)、时间戳、`version` | |

### 13.3 安全不变量

- `WRITE_CAPABILITY` 类 flag 的 `state → ENABLED` 迁移在应用服务层**无条件拒绝**
  (`PRODUCTION_WRITE_DISABLED`),拒绝被审计(DENIED 事件)——因为
  `ProductionWritePolicy` 在本阶段恒为 false;未来 Controlled Write Gate WP 把
  该判定替换为完整链路(Guardrail→Approval→Outbox→Readback→Kill Switch),
  本设计在 §24 ADM-002 行记录该归属;
- `ENABLED → DISABLED` 永远允许(kill 方向永不被门挡住)——这是未来 ADM-007
  Kill Switch 的元数据基础:flag 的关断语义从第一天就是无条件的;
- flag 指向的 scope 实体必须存在且组织一致(FK);CAPABILITY scope 的 flag 不因
  flag 状态而改变 §12.6 的 fail-closed 判定(`isUsable` 的合取项**不含** flag——
  写能力元数据不是可执行授权,I-07/I-08 的双重保证);
- 生命周期/历史:全部状态迁移审计(old/new/reason/actor);RETIRED 终态。

---

## 14. Audit model(审计模型)

### 14.1 `ops.metadata_audit_event`(append-only)

| 字段 | 语义 |
| --- | --- |
| `id` | UUID PK(应用生成) |
| `occurred_at` | `timestamptz NOT NULL DEFAULT now()` — **服务端(数据库)时间**,应用不提供、不可指定 |
| `actor_type` | `OPERATOR` / `SYSTEM`(CHECK;MIGRATION 不写此表——迁移由 Flyway 历史与 Git 审计) |
| `actor_id` | 操作者归因标识(§15.3 校验后的 `X-Operator` 值;SYSTEM 事件为固定组件名) |
| `source_domain` | `organizationaccount` / `identityaccess` / `marketplaceintegration`(CHECK) |
| `action` | `CREATE` / `UPDATE` / `STATUS_CHANGE` / `GRANT` / `REVOKE` / `VERIFICATION_CHANGE` / `DENIED`(CHECK) |
| `entity_type`、`entity_id`、`entity_code` | 目标实体(DENIED 事件允许 entity_id 为空——目标可能不存在) |
| `change_summary` | `jsonb`:字段级 `{field: {old, new}}` 安全表示;敏感面按 §11.3 永不进入(无请求体回显;被拒原值不记录) |
| `denial_code` | DENIED 事件的稳定错误码(§16.4) |
| `reason` | 操作者理由(要求见 §16) |
| `correlation_id` | 既有 `CorrelationId` 值 |
| `evidence_ref` | nullable(验证类事件的证据引用) |

**无行哈希列**:`occurred_at` 由数据库生成、应用角色无 UPDATE
——应用不可能对含 `occurred_at` 的行内容做插入前哈希,而两步写会破坏
append-only 语义。当前验收契约(AC-9/AC-10)不要求行哈希;无验证工具与链的
孤立哈希不提供完整性价值。当前完整性保证 = 权限模型(§17.4:app 仅
INSERT/SELECT)+ 同事务写入(§14.3);行哈希/哈希链——连同其 `occurred_at`
取值来源(改为应用时钟或 DB 触发器)这一前置决策——**整体**属未来审计强化
WP(§22 审计篡改行同步更新)。

### 14.2 模块所有权与端口

`adminobservability` 是唯一所有者(§3.1):写路径
`MetadataAuditRecorder.record(...)`、查询路径 `MetadataAuditQueries.find(filter)`
(按 actor / 时间区间 / entity_type / entity_id / action / source_domain 组合检索,
keyset 分页)——满足 IAM-007 的"按 Actor、Time、Entity、Action 检索"。
业务模块**不得**直读 `ops.metadata_audit_event`(仓储在 `internal`,规则守卫)。

### 14.3 事务语义(两类路径,均为同步端口调用,非事件总线)

| 路径 | 事务行为 | 理由 |
| --- | --- | --- |
| 变更审计(CREATE/UPDATE/STATUS_CHANGE/GRANT/REVOKE/VERIFICATION_CHANGE) | 与业务变更**同一事务**:审计 INSERT 失败 ⇒ 整体回滚 ⇒ 变更不发生(fail closed,I-09/I-10) | "改了但没审计"必须不可表示 |
| 拒绝审计(DENIED) | 独立事务(`REQUIRES_NEW`)在拒绝决定后提交 | 被拒操作没有业务事务可依附;拒绝痕迹不能因主流程回滚而丢失 |

不用 Spring Modulith 事件总线承载审计(默认监听语义为提交后异步,会打破
同事务原子性)——显式取舍,记录于此防实施漂移。

### 14.4 append-only 与前向更正

- 应用角色对该表仅 `INSERT, SELECT`(§17.4)——普通应用路径的 UPDATE/DELETE 在
  数据库层不可执行,负向测试断言(TC-DB-208);
- 审计自身永不更正;录错的**业务**元数据走 forward correction(§7.3),产生新的
  审计事件引用旧事件语境(`reason` 说明更正);
- 迁移角色理论上可触碰该表——控制手段是迁移评审 + governance 校验(未来迁移
  diff 中对 `ops.metadata_audit_event` 的 UPDATE/DELETE 语句是评审阻断项,
  记入 §22 威胁表)。

### 14.5 范围声明

本 WP 审计范围 = 元数据变更 + 拒绝尝试。登录/会话审计、Secret Manager 访问审计、
Raw Replay 审计、平台写审计等属未来 WP,本设计不声称其完成(§24)。

---

## 15. ADM-001 controlled maintenance / query surface(受控维护与查询面)

> 这是本 WP 唯一 FULL 闭合的 Requirement,因此给出**具体工程决定**,不是"以后再保护"。

### 15.1 决定:loopback 绑定的版本化 HTTP command/query API + fail-closed 维护写开关

选择 HTTP 而非 CLI/SQL 脚本的理由:与既有基础(loopback 绑定的 Spring MVC、
correlation、ProblemDetail、结构化日志、集成测试栈)完全同构,可被既有测试与
观测契约覆盖;未来运行时 IAM 落地时,同一 API 面直接加认证层,无迁移断层。
CLI 方案会另造一条与未来产品面平行的通道,退役成本高(妥协残留风险)。

### 15.2 为什么在无认证前提下仍然安全(边界逐层)

| 层 | 机制 | 来源 |
| --- | --- | --- |
| 网络/进程边界 | `server.address: 127.0.0.1`(基线 yaml,既有)——API 只能被同宿主进程访问;不存在公网可达路径。回归测试断言该绑定与 admin 路径同端口同绑定(TC-AD-101) | 既有基础,复用 |
| 维护写开关 | `marketops.metadata-maintenance.write-enabled`:基线 `application.yaml` 显式 `false`(**fail closed:未显式打开的环境一律拒绝变更**);`application-local.yaml` / `-ci.yaml` 显式 `true`。关闭时全部变更命令返回 `MAINTENANCE_WRITE_DISABLED` 并留 DENIED 审计;查询不受此开关限制(ADM-001 查询边界仍可用) | 新增,`shared.MaintenanceWriteGate` |
| 操作者归因 | 变更命令强制头 `X-Operator`(`^[a-z0-9][a-z0-9._-]{0,63}$`,校验失败/缺失 → `OPERATOR_ATTRIBUTION_MISSING`,不回显原值)。**明示:这是归因不是认证**——在 loopback 边界内记录"谁在键盘前",供审计 actor 使用 | 新增 |
| 浏览器面 | admin 路径**不进入** CORS allowlist(既有 CORS 策略仅 GET/OPTIONS 且仅允许 console 源;本设计把 CORS 配置收窄到 `/api/v1/meta/**`,admin 路径零 CORS 头),浏览器跨源不可调用;本 WP 不提供任何前端界面 | 收窄既有配置 |
| 未来 IAM 分离 | 运行时 IAM WP 在同一 API 面加认证/授权过滤层并把 `X-Operator` 归因替换为认证主体;`MaintenanceWriteGate` 的开关语义届时收编进真正的授权判定。本机制**不是最终生产访问控制**,该声明进入 API 文档与 §24 | 边界声明 |

### 15.3 能力范围

维护(command):Organization / Legal Entity / Marketplace Account / Store /
Warehouse / Store↔Warehouse 关联 / 履约声明 / 时区 / 币种 / Service Account /
Scope Grant / Credential 元数据 / Registry 结构行 / Feature Flag。
查询(query):上述全部 + 审计事件检索(§14.2)。

### 15.4 可审计性

每条变更命令 ⇒ 恰一条变更审计事件(同事务);每次被拒 ⇒ 恰一条 DENIED 事件。
`X-Operator` → `actor_id`,`X-Correlation-ID` → `correlation_id`。

### 15.5 不强制 OQ-005

本节机制不选择身份提供商、不实现登录,不为未来 IAM 造成任何绑定;OQ-005 保持
OPEN 且不被本设计隐式关闭。

### 15.6 前端边界

本 WP 不提出 console 界面(健康壳保持现状)。ADM-001 的验收面是 API + runbook
操作程序(`docs/06-runbooks` 新增维护操作页,含 curl 示例与安全边界说明);
管理 UI 属未来 console WP(边界外,非本 WP 递延项)。

---

## 16. API / command / query contracts(API、命令与查询契约)

### 16.1 资源与路径(全部在 `/api/v1/admin/metadata/` 之下)

| 资源 | 命令 | 查询 |
| --- | --- | --- |
| `organizations` | POST 创建;PUT `/{id}` 属性更新;POST `/{id}/status` 状态迁移 | GET 列表(过滤+keyset 分页);GET `/{id}` |
| `legal-entities`、`marketplace-accounts`、`stores`、`warehouses` | 同上形态(创建载荷含父引用) | 同上 + 按父过滤 |
| `store-warehouse-links` | POST 创建;PUT `/{id}` 区间/备注更新;POST `/{id}/status`(END/CANCEL) | GET 按 store/warehouse/mode/时点过滤 |
| `store-fulfillment-declarations` | 同上 | 同上 |
| `service-accounts` | POST;PUT `/{id}`;POST `/{id}/status`(DISABLE/ENABLE/REVOKE);子资源 `allowed-sources`:POST 追加;停用走 POST `/{id}/allowed-sources/{sid}/status`(全 API 无 DELETE,与 I-12 一致) | GET + `/{id}` + 评估结果字段 |
| `scope-grants` | POST(授予);POST `/{id}/revoke` | GET 按主体/权限/资源过滤 |
| `credentials` | POST(创建;必带 `scopeMode`,`STORE_SET` 时必带非空 `storeIds` 且与凭据行同事务生效,§11.1a;可携带 `replacesCredentialId` 开启轮换重叠窗口,§11.4);PUT `/{id}`(非密元数据);POST `/{id}/status`(DISABLE/ENABLE/REVOKE——REVOKE/DISABLE 旧凭据即轮换切换完成);POST `/{id}/scope-mode`(显式扩权/收窄:`STORE_SET→ACCOUNT` 要求范围行已全部撤回,`ACCOUNT→STORE_SET` 必带非空 `storeIds` 同事务生效;`reason` 必填);子资源 `store-scopes`:POST 追加(仅 STORE_SET 凭据)、POST `/{id}/store-scopes/{sid}/status` 撤回(允许撤至空集——结果为 fail-closed 不可用态,非扩权) | GET(响应仅白名单字段;含派生 `rotationStatus`、`scopeUsability` 与 EXPIRED) |
| `capabilities`、`endpoints` | POST 结构行登记;PUT `/{id}`;POST `/{id}/verification`(仅 UNKNOWN↔UNVERIFIED,§12.6) | GET 按平台/状态过滤 |
| `capability-subject-statuses` | POST 结构行(主体=account 或 store 恰一;availability 仅可 UNKNOWN,本 WP) | GET 按能力/主体/平台过滤的矩阵视图 |
| `platform-permission-requirements` | POST 结构行登记(verification 仅可 UNKNOWN/UNVERIFIED) | GET 按平台/目标过滤 |
| `feature-flags` | POST;POST `/{id}/state`(§13.3 约束);POST `/{id}/status` | GET |
| `audit-events` | —(无命令) | GET 按 §14.2 过滤 |

命令/查询分离:命令 = 显式语义 POST/PUT(无模糊 CRUD PATCH);查询无副作用。

### 16.2 命令载荷通用形态

```json
{
  "expectedVersion": 3,
  "reason": "…(状态迁移/更正/授予/撤销/验证变更时必填)",
  "…业务字段…"
}
```

列表查询:`?limit=(1..200,默认50)&after=<上一页末条id>` keyset 分页,稳定排序
`(created_at, id)`;过滤参数按资源列出于实施版 API 文档。

### 16.3 验证与绑定

- 请求 DTO 严格反序列化:**未知字段拒绝**(admin 路径专用 ObjectMapper 配置
  `FAIL_ON_UNKNOWN_PROPERTIES=true`)——mass assignment 与 Secret 夹带的第一道闸;
- Bean Validation 注解 + 应用层语义校验(时区/币种/CIDR/引用格式/状态机);
- 错误响应沿用既有 RFC-9457 ProblemDetail + `correlationId`;**不回显请求内容**。

### 16.4 稳定错误码(`shared.ErrorCode` 增量)

既有:`VALIDATION_FAILED`、`RESOURCE_NOT_FOUND`、`INTERNAL_ERROR`。新增:

| 码 | HTTP | 语义 |
| --- | --- | --- |
| `DUPLICATE_IDENTITY` | 409 | code/native key/引用唯一性冲突(携带既存实体 id) |
| `VERSION_CONFLICT` | 409 | 乐观锁失配 |
| `INVALID_STATE_TRANSITION` | 409 | 状态机拒绝(§18) |
| `EFFECTIVE_RANGE_OVERLAP` | 409 | 生效区间重叠(§7.2) |
| `CROSS_ORGANIZATION_REJECTED` | 409 | 跨组织引用(应用预检;DB 复合 FK 违例同码) |
| `REFERENCED_ENTITY_ACTIVE` | 409 | 存在活跃子级/引用时的退役拒绝 |
| `INVALID_TIMEZONE` / `INVALID_CURRENCY` / `INVALID_COUNTRY` | 400 | §7.4 |
| `SECRET_REFERENCE_INVALID` | 400 | §11.2 格式不符 |
| `SECRET_MATERIAL_SUSPECTED` | 400 | §11.2 疑似密文材料,不回显 |
| `SERVICE_ACCOUNT_INACTIVE` | 409 | 对非 ACTIVE 主体的授予/使用尝试 |
| `UNKNOWN_SCOPE` | 400 | 范围类型/资源不可解析 |
| `CAPABILITY_VERIFICATION_NOT_SUPPORTED` | 409 | 本 WP 无 VERIFIED 路径(§12.6) |
| `PRODUCTION_WRITE_DISABLED` | 409 | 写能力 flag 启用尝试(§13.3) |
| `MAINTENANCE_WRITE_DISABLED` | 403 | 维护写开关关闭(§15.2) |
| `OPERATOR_ATTRIBUTION_MISSING` | 400 | 归因头缺失/非法(§15.2) |

### 16.5 幂等与重复提交

- 创建:业务 `code` 唯一 ⇒ 超时重试撞 `DUPLICATE_IDENTITY`(带既存 id)⇒ 安全;
  不引入独立 Idempotency-Key 存储(显式取舍:维护型低频命令,code 锚点已消除
  重复副作用;Outbox 级幂等键属 WP-P0-003+);
- 更新/状态迁移:`expectedVersion` 前置条件 ⇒ 重放要么同态要么 409,无双重效果;
- 批量:**本 WP 不提供批量命令**(每命令一实体,失败面清晰;onboarding 批量录入
  由 runbook 脚本按单命令循环完成)——显式取舍,消除部分成功语义的复杂面。

### 16.6 无 Marketplace HTTP Client

本 API 面不含任何对外平台调用;Registry 维护是纯元数据登记。

---

## 17. Database constraints and least privilege(数据库约束与最小权限)

### 17.1 主键/身份

全表 `id uuid PRIMARY KEY`(应用生成);参考表以 `code text PRIMARY KEY`
(确定性种子,无 UUID)。无 sequence。

### 17.2 外键与组织隔离(核心机制)

每张携带 `organization_id` 的表,其父引用一律使用**复合外键**,使"子行与父行
属于不同组织"在数据库层不可表示:

```sql
-- 父表带辅助唯一约束(示意)
ALTER TABLE core.legal_entity
  ADD CONSTRAINT legal_entity_id_org_uk UNIQUE (id, organization_id);

-- 子表复合 FK(示意:marketplace_account → legal_entity)
ALTER TABLE core.marketplace_account
  ADD CONSTRAINT marketplace_account_legal_entity_fk
  FOREIGN KEY (legal_entity_id, organization_id)
  REFERENCES core.legal_entity (id, organization_id);
```

同一模式应用于:store→account、warehouse→legal_entity、
store_warehouse_link→{store,warehouse}(两条复合 FK 共享同一 `organization_id`
列 ⇒ 跨组织关联不可表示)、store_fulfillment_declaration→store、
scope_grant→{service_account, 五类资源列}、credential_metadata→account、
credential_store_scope→{credential, store}(本表实列 `marketplace_account_id`
经两条复合 FK 同时钉住凭据与 Store 的账户 ⇒ 跨账户范围不可表示,§11.1a)、
capability_subject_status→{account | store}(恰一主体)、
platform_permission_requirement→{capability | endpoint}、feature_flag→scope 引用。

**Registry 同平台命名空间不变量(数据库级)**:平台码不是描述性标签而是
关系约束的一部分。辅助唯一键 `platform_capability(id, platform_code)`、
`platform_endpoint(id, platform_code)`、`core.marketplace_account(id, platform_code)`
之上,以下引用一律携带本表 `platform_code` 组成复合 FK,使跨平台链接
(如 Ozon 端点指向 WB 能力、替代关系跨平台、证据行标注平台与目标归属不符、
Ozon 账户挂 WB 能力状态)在数据库层**不可表示**,绕过应用服务的直接 SQL
同样失败(TC-DB-217):

```text
platform_endpoint(capability_id, platform_code)            → platform_capability(id, platform_code)
platform_capability(replacement_capability_id, platform_code) → platform_capability(id, platform_code)
platform_endpoint(replacement_endpoint_id, platform_code)  → platform_endpoint(id, platform_code)
platform_permission_requirement(capability_id, platform_code) → platform_capability(id, platform_code)
platform_permission_requirement(endpoint_id, platform_code)   → platform_endpoint(id, platform_code)
capability_subject_status(capability_id, platform_code)    → platform_capability(id, platform_code)
capability_subject_status(marketplace_account_id, platform_code)
                                                           → core.marketplace_account(id, platform_code)
```

(可空引用列上的复合 FK 采用 PostgreSQL 默认 MATCH SIMPLE 语义:引用列为
NULL 时不检查,恰好匹配"可选引用、存在即同平台"的要求。Store 主体的平台
一致性经 store→account→platform 两跳,超出单表 FK 表达力,归 §17.3 应用级
清单并由 TC-MI-121 补偿。)其余平台码引用(各表 `platform_code` →
`core.marketplace_platform.code`)为普通单列 FK——参考表无组织维度。
应用层预检提供友好错误(`CROSS_ORGANIZATION_REJECTED`),数据库兜底并发窗口。

### 17.3 约束清单(类别级)

| 类别 | 机制 |
| --- | --- |
| 唯一/native key | §6.2 全表;部分唯一以 partial unique index 实现(`WHERE ... IS NOT NULL AND status <> 'RETIRED'` 等) |
| 生效区间 | CHECK `effective_from < effective_to`(to 非空时);重叠经 §7.2 Exclusion Constraint |
| 枚举 | 参考表 FK(fulfillment_mode、permission_kind、marketplace_platform)或 CHECK(状态、tri-state)——**有种子行的用 FK,纯状态字用 CHECK**(状态属状态机,不需要运营可扩展性) |
| 条件完整性 | `num_nonnulls` CHECK(scope 恰一资源列、flag scope 矩阵、verification 出处三元组、DISABLED⇒reason 非空) |
| 格式 | `code`/币种/引用格式的 DB CHECK 与应用校验双写(DB 保底,应用给友好错误) |
| 并发 | `version bigint NOT NULL DEFAULT 0`;仓储 `UPDATE ... WHERE id=? AND version=?`,零行更新 ⇒ `VERSION_CONFLICT` |
| 审计 append-only | 权限层实现(§17.4)+ 负向测试;不额外用触发器(应用角色本就无 UPDATE/DELETE 权限,触发器是冗余防线且增加迁移面——显式取舍) |

**application-only 不变量及补偿**:时区权威校验(§7.4,补偿 TC-OM-112)、
Secret 疑似材料启发式(§11.2,补偿 TC-MI-105/106)、状态机全量迁移矩阵
(§18,DB 只有枚举 CHECK,迁移合法性在应用层;补偿 = §18 全矩阵参数化测试
TC-OM-120 族)、凭据轮换链约束(同账户/同用途/目标非 REVOKED,§11.4,补偿
TC-MI-111)、凭据范围与 `scope_mode` 的联动("ACCOUNT 无活跃范围行"“STORE_SET
空集不可用",跨行条件,§11.1a,补偿 TC-MI-112/114)、能力主体与 `applies_to`
一致性及 Store 主体的平台一致性(§12.5,跨表/两跳 join 条件,补偿 TC-MI-121)。
理由均为:规则需要运行时知识或跨表语境(tzdata、启发式、迁移语境、join 条件),
SQL 单表约束无法完备表达;每条都有确定性测试补偿。

### 17.4 `marketops_app` 对象级授权(逐类;零笼统、零默认)

| 对象 | 授权 | 说明 |
| --- | --- | --- |
| `core`/`iam`/`platform` 实体与关联表 | `SELECT, INSERT, UPDATE` | **全库无 DELETE**(I-12;forward correction) |
| 参考表(**恰四张**:`core.marketplace_platform`、`core.fulfillment_mode`、`iam.permission_kind`、`platform.credential_purpose`) | **仅 `SELECT`** | 种子只由迁移写;app 无 INSERT/UPDATE/DELETE;各自迁移文件(V0004/V0005/V0006)的授权语句与本表一致,逐表负向测试(TC-DB-216) |
| `platform.capability_verification_event` | `SELECT, INSERT` | 验证历史 append-only |
| `ops.metadata_audit_event` | `SELECT, INSERT` | 审计 append-only |
| 其余一切(含 `flyway_schema_history`、`raw/staging/ledger/mart` 全部) | 无 | 维持 V0001 姿态 |

- 每条 GRANT 逐表逐权限写在引入该表的迁移中,并带一行注释说明其对应不变量;
- **不使用** `ALTER DEFAULT PRIVILEGES`、不授 schema `CREATE`、不授列级例外;
- `marketops_migration` 保持对象属主(`AUTHORIZATION` 语义延续 V0001);
- 负向测试:app 角色对每类表的被禁操作逐一断言 `42501`(§23 TC-DB-205…209)。

### 17.5 索引(按查询模式)

- 全部 FK 列 btree 索引(PostgreSQL 不自动为 FK 建索引);
- 列表面:各实体 `(organization_id, status, created_at, id)`;
- 关联时点查询:`store_warehouse_link (store_id, status, effective_from)` +
  Exclusion Constraint 自带 GiST 索引;
- 审计检索:`(entity_type, entity_id, occurred_at DESC)`、
  `(actor_id, occurred_at DESC)`、`(occurred_at)`、`(action, occurred_at DESC)`;
- 唯一/部分唯一索引即 §6.2 清单。不建投机索引(元数据量级下按需增补)。

---

## 18. Lifecycle / state machines(生命周期与状态机)

通用规则:迁移仅经应用服务;每次迁移审计(old/new/reason);非法迁移 →
`INVALID_STATE_TRANSITION` + 不落库;并发迁移由 `version` 串行化;
生效区间对象的"当前是否生效"是**派生问题**(§7.1),不作为状态列冗余。

### 18.1 Organization / Legal Entity / Marketplace Account / Store / Warehouse(同一族)

```text
ACTIVE ⇄ SUSPENDED          (SUSPEND/RESUME,reason 必填)
ACTIVE | SUSPENDED → RETIRED (终态)
```

- RETIRED 前置条件:不存在非 RETIRED 子级、无 ACTIVE 关联/授予/凭据引用它
  (违者 `REFERENCED_ENTITY_ACTIVE`;强制级联退役**不提供**——操作者必须逐级
  显式退役,每级留审计);
- SUSPENDED 的语义 = 运营暂停(元数据可读、可被查询,不可作为新关联/授予/凭据
  的目标;已有生效关联不被系统自动终止——自动化干预属未来 WP,元数据层不
  伪造运营决定);
- RETIRED 不可逆;误退役的恢复路径 = 以新 code 重建 + 审计引用(forward correction)。

### 18.2 Store↔Warehouse 关联 / 履约声明

```text
ACTIVE → ENDED      (设置 effective_to 或显式 END;正常结束)
ACTIVE → CANCELLED  (录入错误;终态;行保留)
ENDED、CANCELLED 均为终态
```

### 18.3 Scope Grant

```text
ACTIVE → REVOKED(终态;reason 必填)
```

到期(effective_to 过去)是派生失效,不改写状态;重新授予 = 新行。

### 18.4 Service Account

```text
ACTIVE ⇄ DISABLED   (DISABLE/ENABLE,reason 必填)
ACTIVE | DISABLED → REVOKED (终态)
EXPIRED = 派生态(§10.3),不落库,不参与迁移矩阵
```

fail-closed 含义:评估非 ACTIVE ⇒ 一切消费拒绝;REVOKED 主体的活跃授予在同一
事务内联动置 REVOKED(应用服务保证 + 测试断言,防止悬挂授予);allowed-source
行不联动(历史声明保留,评估在账户层已拒绝)。

### 18.4a Service Account Allowed Source

```text
ACTIVE → WITHDRAWN(终态;reason 必填;审计)
```

重新启用同一来源 = 登记新 ACTIVE 行(forward correction,无 DELETE、无复活)。

### 18.5 Credential 元数据

```text
status:      ACTIVE ⇄ DISABLED → REVOKED(终态;REVOKED 释放 secret_reference 唯一域)
EXPIRED    = 派生态(expires_at),不落库
scope_mode:  STORE_SET → ACCOUNT(显式扩权命令;要求全部范围行已 WITHDRAWN,
             reason 必填,独立审计)
             ACCOUNT → STORE_SET(显式收窄命令;同命令携带非空初始 storeIds,
             同事务生效)
轮换       = replaces_credential_id 链 + status 状态机(§11.4);无独立轮换状态列
             ——rotationStatus(STABLE | BEING_REPLACED)为查询层派生输出
```

`credential_store_scope`:`ACTIVE → WITHDRAWN`(终态,reason 必填;重加=新行;
撤回最后一行产生空集 fail-closed 不可用态而非扩权,§11.1a)。

### 18.6 Capability / Endpoint / Account-Capability 验证

§12.6;补充:`status: ACTIVE → RETIRED` 终态;RETIRED 行保留验证末值与全部
出处历史。

### 18.7 Feature Flag

```text
state:  DISABLED → ENABLED   (OPERATIONAL:允许;WRITE_CAPABILITY:本阶段无条件拒绝并审计)
        ENABLED → DISABLED   (无条件允许——kill 方向永不设闸)
status: ACTIVE → RETIRED     (终态;RETIRED 前 state 必须为 DISABLED)
```

---

## 19. Failure and recovery model(失败与恢复模型)

通用列语义——检测:何处发现;事务:回滚/拒绝行为;错误:操作者可见稳定码;
审计:DENIED 独立事务(§14.3);恢复:前向路径;重试:重复提交是否安全。

| # | 失败 | 检测 | 事务 | 错误 | 审计 | 观测(§20) | 恢复/重试 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| FM-01 | 跨组织关系 | 应用预检 + 复合 FK | 回滚 | `CROSS_ORGANIZATION_REJECTED` 409 | DENIED | `metadata_denied{reason=cross_org}` | 修正引用后重试;安全 |
| FM-02 | 非法/未知 scope | 校验(类型/资源解析失败) | 拒绝 | `UNKNOWN_SCOPE` 400 | DENIED | 同上 | 安全 |
| FM-03 | 过期 Service Account 的授予/使用 | `evaluate()` ≠ ACTIVE | 拒绝 | `SERVICE_ACCOUNT_INACTIVE` 409 | DENIED | `service_account_rejected{state=expired}` | 换主体/续期(显式命令)后重试 |
| FM-04 | 禁用 Service Account | 同上 | 拒绝 | 同上 | DENIED | `{state=disabled}` | 同上 |
| FM-05 | 明文 Secret / 疑似密材载荷 | §11.2 启发式 + 未知字段拒绝 | 拒绝;**原值不落任何持久面/日志** | `SECRET_MATERIAL_SUSPECTED` / `VALIDATION_FAILED` 400 | DENIED(仅字段名+规则名) | `secret_material_rejected` | 改用合法引用;安全 |
| FM-06 | 未知 Capability 引用 | FK/解析失败 | 拒绝 | `RESOURCE_NOT_FOUND` 404 | DENIED | `capability_denied{state=unknown}` | 先登记结构行 |
| FM-07 | UNVERIFIED Capability 消费尝试 | `isUsable()` 恒假(§12.6) | 拒绝 | `CAPABILITY_VERIFICATION_NOT_SUPPORTED` 409 | DENIED | `capability_denied{state=unverified}` | 平台 WP 证据后才可能变化 |
| FM-08 | 非法 Store/Warehouse 关联(实体非 ACTIVE、模式非法) | 应用校验 + FK | 回滚 | `INVALID_STATE_TRANSITION` / `VALIDATION_FAILED` | DENIED | `metadata_denied{reason=invalid_link}` | 修正后重试 |
| FM-09 | 生效区间重叠 | 应用预检 + Exclusion Constraint | 回滚 | `EFFECTIVE_RANGE_OVERLAP` 409 | DENIED | `metadata_denied{reason=overlap}` | 调整区间;安全 |
| FM-10 | 重复身份/native key | 唯一约束 | 回滚 | `DUPLICATE_IDENTITY` 409(含既存 id) | DENIED | `metadata_denied{reason=duplicate}` | 创建重试幂等安全(§16.5) |
| FM-11 | 乐观版本过期 | `UPDATE…WHERE version` 零行 | 回滚 | `VERSION_CONFLICT` 409 | DENIED | `metadata_denied{reason=stale_version}` | 重读最新版后重试 |
| FM-12 | 生产写开启尝试(flag ENABLED / 配置 true) | §13.3 服务判定;§13.1 启动校验 | 拒绝;配置态直接**启动失败** | `PRODUCTION_WRITE_DISABLED` 409 | DENIED | `production_write_denied` | 无恢复路径(设计内行为,直至 Controlled Write Gate WP) |
| FM-13 | 非法时区/币种/国家码 | §7.4 校验 | 拒绝 | `INVALID_TIMEZONE` 等 400 | DENIED | `metadata_denied{reason=format}` | 修正后重试 |
| FM-14 | 迁移撞既有元数据(升级路径上 V0004+ 的唯一冲突等) | Flyway 失败 | PostgreSQL 事务性 DDL 整体回滚,无部分对象、无历史行(WP-P0-001 已确证语义) | 启动失败,日志含迁移版本 | —(Flyway 历史即证据) | `migration/startup` 健康信号 | 修复库状态后重跑;V0002–V0006 对 WP-P0-001 合法终态**不可能**冲突(其上不存在这些对象),冲突即库被污染 ⇒ 与 V0001 同一处置哲学:失败暴露而非容忍 |
| FM-15 | 审计写失败(变更路径) | INSERT 异常 | **整体回滚,业务变更不发生** | `INTERNAL_ERROR` 500 | —(见观测) | `audit_write_failed`(ERROR 日志+计数) | 数据库恢复后重试;不存在"改了没审计"态 |
| FM-16 | 凭据 Store 范围指向他账户 Store | 应用预检 + `(store_id, marketplace_account_id)` 复合 FK | 回滚 | `CROSS_ORGANIZATION_REJECTED` 409 | DENIED | `metadata_denied{reason=cross_account_scope}` | 修正 Store 引用后重试;安全 |
| FM-17 | 非法轮换链(`replacesCredentialId` 不存在/异账户/异用途/已 REVOKED) | 应用校验(§11.4) | 拒绝 | `VALIDATION_FAILED` / `RESOURCE_NOT_FOUND` / `INVALID_STATE_TRANSITION` 按因 | DENIED | `metadata_denied{reason=invalid_rotation_chain}` | 修正目标后重试;安全 |
| FM-18 | 能力主体与 `applies_to` 不一致 | 应用校验(§12.5) | 拒绝 | `VALIDATION_FAILED` 400 | DENIED | `metadata_denied{reason=subject_mismatch}` | 以正确主体粒度登记;安全 |
| FM-19 | `STORE_SET` 空活跃集凭据的消费/匹配尝试 | §11.1a/§11.5 范围评估 | 拒绝(fail closed,永不回落账户范围) | 查询层呈现 `scopeUsability=NO_ACTIVE_STORE_SCOPE` | —(评估非命令) | `credential_scope_unusable`(WARN+计数) | 追加范围行或显式模式变更;安全 |
| FM-20 | 违反范围模式的命令(向 ACCOUNT 凭据追加范围行;未清场即 `STORE_SET→ACCOUNT` 扩权;收窄命令空 `storeIds`) | 应用校验(§11.1a) | 拒绝 | `INVALID_STATE_TRANSITION` / `VALIDATION_FAILED` 按因 | DENIED | `metadata_denied{reason=scope_mode_violation}` | 按 §11.1a 顺序执行显式命令;安全 |

**永不**静默改写历史、永不把未知状态强制为已知值(I-07/I-09);全部 DENIED
路径不修改任何业务行。

---

## 20. Observability(可观测性)

### 20.1 结构化日志事件(固定事件名 + 安全键值;沿用既有日志契约)

| 事件 | 级别 | 键(全部有界、无 Secret/PII/请求体) |
| --- | --- | --- |
| `metadata_change_recorded` | INFO | `sourceDomain`、`entityType`、`entityId`、`action`、`actorId`、`correlationId` |
| `metadata_change_denied` | WARN | 上述 + `denialCode`(枚举) |
| `service_account_rejected` | WARN | `serviceAccountId`、`evaluation`(枚举)、`correlationId` |
| `capability_denied` | WARN | `capabilityId`、`verificationState`、`correlationId` |
| `production_write_denied` | WARN | `flagCode`、`scopeKey`、`actorId`、`correlationId` |
| `secret_material_rejected` | WARN | `fieldName`、`ruleName`、`correlationId`(**无值回显**) |
| `credential_scope_unusable` | WARN | `credentialId`、`scopeMode`、`correlationId`(STORE_SET 空活跃集评估拒绝,§11.1a) |
| `audit_write_failed` | ERROR | `sourceDomain`、`entityType`、`action`、`correlationId`、`exceptionClass` |
| `maintenance_write_disabled_denied` | WARN | `path 模板名`(非原始 URL)、`actorId`、`correlationId` |

### 20.2 指标(进程内 Micrometer 计数器;不新增暴露端点)

`marketops.metadata.changes{domain,action}`、
`marketops.metadata.denials{code}`(code=稳定错误码枚举,基数有界)、
`marketops.audit.write.failures`、`marketops.serviceaccount.rejections{state}`、
`marketops.capability.denials{state}`。指标导出仍属既有推迟项;计数器先行注册,
供未来观测 WP 消费——标签集全部为闭集枚举,无无界标签。

### 20.3 迁移/启动健康与新鲜度

- 既有 `/api/v1/meta/status` 的 `migration.currentVersion` 自动反映 `0006`
  (白名单字段不变、无新增键——meta 契约不因本 WP 扩面);
- Registry/凭据的验证与新鲜度(`last_verified_at`、`expires_at`)经查询 API 可见;
  主动告警属未来观测 WP。

### 20.4 红线(重申并由测试守卫)

无 Secret、无 PII、无完整请求体、无无界标签、审计真相不在日志中重复成第二权威
(日志事件只携带引用,detail 以审计表为准)。log-capture 测试(TC-OB-101…104)
断言:admin 命令往返产生的全部日志行不含请求体片段、不含被拒原值、不含
`secret-ref://` 之外的引用形态。

---

## 21. Migration / compatibility / forward recovery(迁移、兼容与前向恢复)

- 计划:§4.3 五个文件,V0001 不变;对象创建顺序 = 扩展 → 审计 → core → iam →
  platform(FK 依赖序);约束/索引与表同迁移创建;授权随对象(§17.4);
- **两条测试路径**:干净库(V0001→V0006 一次施加)与升级库(先只到 V0001——
  即 WP-P0-001 合法终态——再施加其余),终态经 schema dump 归一化比较相等
  (TC-DB-215);
- 种子 = 四张参考表的固定枚举行(`core.marketplace_platform` §5.2、
  `core.fulfillment_mode` §8.1、`iam.permission_kind` §9.2、
  `platform.credential_purpose` §11.1),确定性、无时间戳值、
  无业务清单;**无生产业务种子**;迁移零远程调用;
- 事务性:每迁移单事务,失败整体回滚(FM-14);
- 前向恢复:部署后元数据纠错一律走 §7.3/§14.4 的 forward correction;
  不设计 down-migration,不假设破坏性回滚;应用发布兼容性:V0002–V0006 纯新增,
  旧代码(WP-P0-001 应用)在新库上继续工作(其不触及新表),满足分阶段部署;
- 迁移文件命名沿用 `V<4 位>__<snake_case>.sql`;合并 `main` 后永不改写。

---

## 22. Security / privacy threat analysis(安全与隐私威胁分析)

| 威胁 | 预防 | 检测 | 恢复 | 当前 WP / 未来归属 |
| --- | --- | --- | --- | --- |
| Credential/Secret 泄漏 | 只存不透明引用(§11.2);疑似材料拒绝;禁止面逐面机制(§11.3);governance/Push Protection 扫描 | 日志/审计事件;Secret 扫描告警 | 撤销引用指向的外部 Secret(外部程序)+ REVOKE 元数据 + 审计 | 引用契约=本 WP;真实 Secret 生命周期=OQ-006 后 WP |
| 权限放大 | deny-by-default;权限互不蕴含;无角色捆绑;授予强制审计+reason;组织一致复合 FK | 审计检索(按主体);`metadata_denied` 指标 | REVOKE + forward correction | 元数据不变量=本 WP;运行时执行=IAM WP |
| 跨组织访问/关联 | 复合 FK(§17.2)+ 应用预检 | DENIED 审计 + 指标 | 数据不可表示,无污染需恢复 | 本 WP |
| 未认证维护 | loopback 绑定;维护写开关 fail closed;归因强制;CORS 收窄;无前端面 | 拒绝审计;绑定回归测试 | 开关关闭即冻结变更面 | 当前边界=本 WP;最终访问控制=IAM WP |
| Service Account 滥用 | 强制到期;fail-closed 评估;来源声明;REVOKE 联动撤授予 | `service_account_rejected` 信号 | DISABLE/REVOKE 命令 | 元数据=本 WP;网络/Token 执行=未来 |
| 过期凭据元数据被继续引用 | 派生 EXPIRED 一律拒绝消费;模糊并存 fail closed(§11.5) | 查询可见到期与轮换链;未来告警 WP | 登记后继凭据(`replaces_credential_id`)+ 旧凭据 DISABLE/REVOKE | 本 WP 提供元数据;告警=观测 WP |
| Capability 验证欺骗(伪造 VERIFIED) | 本 WP 无 VERIFIED 写路径(§12.6);未来路径要求出处三元组 CHECK + 验证事件 append-only | 验证事件历史检索 | 降级 UNVERIFIED + reason | 结构=本 WP;证据填充=平台 WP |
| Feature Flag / 写能力滥用 | WRITE_CAPABILITY 启用无条件拒绝;配置 true ⇒ 启动失败(§13.1);flag 不进入 `isUsable` 合取 | `production_write_denied` 审计+指标 | kill 方向无闸 | 本 WP;完整链=Controlled Write Gate WP |
| 审计篡改 | app 角色无 UPDATE/DELETE;变更同事务写入(无行哈希列,§14.1) | 负向权限测试 | 审计不可改;错误业务数据前向更正 | 权限面=本 WP;行哈希/哈希链/存档=审计强化 WP;迁移角色路径=评审控制(§14.4) |
| 日志/错误泄露 | 既有安全错误契约;事件键白名单;无请求体;无原值回显 | log-capture 测试 | — | 本 WP |
| Mass assignment | 严格 DTO 绑定拒绝未知字段(§16.3) | `VALIDATION_FAILED` 观测 | — | 本 WP |
| ID 猜测/枚举 | UUID 身份;404 语义不区分"不存在/不可见"(现无授权语境,记录未来 IAM 注意项) | — | — | 本 WP + IAM WP |
| D-15 公库泄漏 | 库内零 Secret/清单/生产数据;fixture 全合成;证据脱敏九类清单沿用 | governance + Secret Scanning | 按 SECURITY.md 流程 | 持续控制(非本 WP 递延) |
| Fixture 被生产数据污染 | fixture 政策:仅合成;`secret-ref://test/...`;评审断言 | governance 扫描 | 移除+历史处置流程 | 本 WP 强化既有政策 |

---

## 23. Test and evidence matrix(测试与证据矩阵)

层级:`U`=单元/Domain;`P`=不变量/属性;`IT`=PostgreSQL Testcontainers 集成;
`ARCH`=架构;`GOV`=governance 校验器;`CTRL`=Controller 审查。全部确定性
(固定种子、注入 Clock/IdGenerator、Testcontainers `postgres:18.4` 复用既有
`PostgresContainerSupport`)。**本设计不声称任何测试已运行。**

### 23.1 验收准则映射(WP §10 的 16 条 → 证据)

| AC | 测试 ID(代表) | 层 | 确定性设置 → 预期 |
| --- | --- | --- | --- |
| 1 基数可配置、无 1:1 假设 | TC-OM-101 | U/IT | 1 org / 2 legal entity / 每 LE 2 账户 / 每账户 0..3 store / 仓库 M:N 关联全建成功;无任何唯一约束阻止多元 |
| 2 身份/native key 唯一与引用完整 | TC-OM-102、TC-DB-210 | IT | §6.2 每条约束的正/反用例;违约 → 约束名断言 |
| 3 跨组织拒绝+审计 | TC-OM-103、TC-DB-211 | U/IT | 应用预检 409 + DENIED 事件;绕过应用直插 DB → FK 违例 |
| 4 无隐式特权放大 | TC-IA-101、TC-MI-112 | U/P/IT | 权限对不蕴含矩阵(5×5 全假)+ 授予不派生授予的代码路径断言 + 凭据范围永不隐式扩大(§11.1a:空集 fail closed、扩权仅显式命令) |
| 5 Service Account fail closed | TC-IA-102…105 | U/IT | 过期/禁用/撤销/未知 四态评估全拒绝;REVOKE 联动撤授予 |
| 6 无明文 Secret 任何面 | TC-MI-104…106、TC-OB-103 | U/IT | 疑似材料拒绝且无回显;响应字段白名单;log-capture 全量断言 |
| 7 UNKNOWN/UNVERIFIED fail closed 且保出处 | TC-MI-101…103 | U/IT | `isUsable` 恒假;VERIFIED 写路径 409;验证事件 append-only |
| 8 production_write 不可覆盖 | TC-MI-107、TC-CF-101 | U/IT | 写 flag 启用 → 409+DENIED;属性 true → 上下文启动失败 |
| 9 变更全审计 | TC-AU-101 | IT | 每命令恰一事件,actor/time/entity/action/summary 齐备 |
| 10 审计 append-only | TC-DB-208、TC-AU-102 | IT | app 角色 UPDATE/DELETE → `42501`;审计失败⇒业务回滚 |
| 11 迁移增量+V0001 不变+干净库证据 | TC-DB-201、TC-DB-215 | IT | 干净/升级双路径终态相等;V0001 文件 hash 未变(GOV) |
| 12 对象级最小权限、零笼统授权 | TC-DB-205…209 | IT | 逐表逐权限正/负断言;全库无 DELETE 授权;参考表只读 |
| 13 架构边界防平台/Secret 入 Domain | TC-AR-201…203 | ARCH | 既有 7 规则 + Modulith verify 对新模块通过;仓储跨模块访问 fixture 失败 |
| 14 正/负/失败态/变异测试映射证据 | 本矩阵全部 | — | §19 每个 FM-xx 至少一条测试(TC-FM-xx 族) |
| 15 无隐藏范围 | TC-GOV-201 | GOV/CTRL | 文件清单对照 §26;无 adapter/raw/worker/UI 文件 |
| 16 维护面不可公网未认证到达 | TC-AD-101…104 | IT | loopback 绑定断言;开关关→403+审计;归因缺失→400;admin 路径无 CORS 头 |

### 23.2 追加证据行(要求项逐条)

| 要求 | 测试 ID | 层 |
| --- | --- | --- |
| Domain 状态机全矩阵(每聚合允许/拒绝迁移) | TC-OM-120、TC-IA-120、TC-MI-120 | U(参数化全枚举) |
| 生效区间重叠(应用+DB 双层)/ 开区间 / 邻接不算重叠 | TC-OM-110、TC-DB-212 | U/IT |
| 时区/币种/国家码校验(合法、非法、NULL=UNKNOWN 呈现) | TC-OM-112 | U |
| 命令/查询契约(每资源:创建/更新/状态/查询/分页/过滤/错误码映射) | TC-AD-110 族 | IT(MockMvc/完整栈) |
| 严格绑定拒绝未知字段(含 `token`/`password` 键) | TC-AD-105 | IT |
| 幂等:创建重试、版本重放 | TC-AD-106 | IT |
| upgrade-from-WP-P0-001:V0001 态库施加 V0002+ | TC-DB-215 | IT |
| grants 负向:app 建表/DDL/触碰 flyway 历史(回归 WP-P0-001) | 既有 TC-DB-104…117 保持 + TC-DB-205 族 | IT |
| Secret 引用格式全边界(合法/超长/空白/Base64 样式/PEM) | TC-MI-105 | U |
| 序列化白名单:凭据/授予响应 JSON 键集合恰等于契约 | TC-MI-104 | IT |
| 凭据重叠轮换:同账户同用途两条 ACTIVE 并存被接受;`replaces_credential_id` 链建立;旧凭据 DISABLE 后派生 rotationStatus 正确;凭据表上无防重叠约束的负向证明(插入即成功) | TC-MI-110 | IT |
| 轮换链约束:目标不存在/异账户/异用途/已 REVOKED 逐一拒绝 | TC-MI-111 | U/IT |
| 凭据范围契约(§11.1a 全矩阵):`ACCOUNT`/`STORE_SET` 显式声明;STORE_SET 创建原子(凭据+初始范围同事务,`storeIds` 空被拒);**STORE_SET 空活跃集 = `NO_ACTIVE_STORE_SCOPE` 不可用,消费评估拒绝且永不回落账户范围**;撤回最后一行 ⇒ 不可用而非扩权;向 ACCOUNT-模式凭据追加范围行被拒;显式扩权命令要求先清场、显式收窄命令携带非空集;WITHDRAWN 后重加为新行;`secret_reference` 唯一性与多 Store 无冲突 | TC-MI-112 | U/IT |
| 凭据范围并发与直接 SQL:并发"撤回最后一行 × 消费评估"竞争下评估结果只可能是 USABLE 或 fail-closed(串行化于 `version`/事务);绕过应用直插异账户 Store 范围行被复合 FK 拒绝(`42503`/FK 违例) | TC-MI-114 | IT |
| 凭据用途分类:五种子值存在;`CREDENTIAL_ADMIN` 不是合法用途(FK 拒绝);参考表对 app 只读 | TC-MI-113、TC-DB-216 | IT |
| 能力主体状态:account/store 主体各自可登记;恰一主体 CHECK;`applies_to` 一致性全矩阵(含 UNKNOWN ⇒ availability 锁定 UNKNOWN);Store 主体平台一致性(两跳 join 校验);部分唯一约束 | TC-MI-121 | U/IT |
| 平台权限要求证据结构:登记 UNKNOWN/UNVERIFIED 行;VERIFIED 无出处被 CHECK 拒绝;`platform.*` 对 `iam.*` 零引用(信息模式断言:platform schema 无任何指向 iam schema 的 FK) | TC-MI-122、TC-DB-218 | IT |
| **Registry 同平台命名空间(§17.2)**:直接 SQL 构造跨平台 endpoint→capability、跨平台 replacement(capability/endpoint)、证据行平台与目标不符、跨平台账户主体状态——逐一被复合 FK 拒绝;同平台等价操作成功(正向对照) | TC-DB-217 | IT |
| Allowed-source 生命周期:ACTIVE→WITHDRAWN(reason 必填);重加新行;`(service_account_id,cidr)` 活跃部分唯一;WITHDRAWN 不可复活 | TC-IA-106 | IT |
| 迁移 FK 序:V0002→V0006 逐个按序施加,每步成功(FK 目标先于 FK 存在的可执行证明) | TC-DB-214 | IT |
| DENIED 独立事务:主流程回滚后拒绝事件仍在 | TC-AU-103 | IT |
| 审计检索(actor/time/entity/action 四维过滤+keyset) | TC-AU-104 | IT |
| Modulith verify + 新 named interface(audit) | TC-AR-201 | ARCH |
| 既有 health/console 回归(meta 白名单不变) | 既有 MetaStatus 测试保持 | U/IT |
| 三条全局硬规则(validate_production_readiness.py 对新文件通过) | TC-GH-001…003 | GOV |
| 覆盖率门(80%/70%)在新模块上保持 | CI verify | CI |

每行未来证据 = surefire/failsafe 报告 + CI run 链接 + `docs/07-phase-evidence/WP-P0-002/`
脱敏摘录(沿用九类清单)。

---

## 24. Requirement closure conclusion(需求闭合结论)

闭合契约原样保留(与规范 WP §5 一致):

| Requirement | WP-P0-002 closure | What this WP is responsible for | Later full-closure responsibility |
| --- | --- | --- | --- |
| IAM-001 | **PARTIAL** | Identity/lifecycle/association metadata and authorization-scope attachment points | Later Controller-selected runtime IAM capability for real user-access enforcement |
| IAM-004 | **PARTIAL** | Read/Write/Finance/Ads/Credential Admin taxonomy and no-amplification invariants | Later runtime IAM authorization enforcement |
| IAM-006 | **PARTIAL** | Service Account identity/purpose/scope/allowed-source/lifecycle/expiry metadata | Later IAM/Credential runtime integration for token/Secret/network enforcement |
| IAM-007 | **PARTIAL** | Metadata-change audit for identities/scopes/Service Accounts/Credential references | Later runtime IAM + feature WPs for login/session and other sensitive-operation audit |
| INT-002 | **PARTIAL** | Endpoint/Capability Registry structure + `UNKNOWN`/`UNVERIFIED` semantics | WP-P0-005/WP-P0-006 for verified platform capability evidence/population |
| INT-003 | **PARTIAL** | Opaque Credential reference + no-plaintext/non-disclosure contract | Later Credential runtime integration after OQ-006 for actual Secret Manager retrieval |
| ADM-001 | **FULL** | Controlled Organization/Account/Store/Warehouse/Timezone/Currency maintenance/query boundary with audit and fail-closed current access boundary | WP-P0-002 when implementation/evidence actually pass |
| ADM-002 | **PARTIAL** | Feature Flag + Capability metadata; production writes off | WP-P0-003 for Job Schedule/Backfill; platform WPs for verified Capability evidence |

逐条 PARTIAL 说明(实施通过后的完成面 / 未完成面 / 保留闭合者 / 仍缺证据):

- **IAM-001**:完成=五类实体身份、生命周期、关联与 scope 附着点经确定性测试证明;
  未完成=用户认证、运行时授权执行、行级访问;保留=未来运行时 IAM WP;仍缺证据=
  真实用户在执行层被限制在授予范围内的运行时测试——**因此不得描述为"访问控制
  已实现"**。
- **IAM-004**:完成=五权限分类、互不蕴含、防放大元数据不变量;未完成=RBAC/ABAC
  决策执行;保留=运行时 IAM WP;仍缺=执行层越权负向测试。
- **IAM-006**:完成=Service Account 全元数据 + fail-closed 评估;未完成=Token 签发、
  Secret 检索、网络来源执行;保留=IAM/Credential 运行时 WP;仍缺=运行时拒绝证据。
- **IAM-007**:完成=元数据变更与拒绝的 append-only 审计与四维检索;未完成=登录/
  会话/其它敏感操作审计;保留=运行时 IAM 与各特性 WP;仍缺=该等事件源存在后的
  端到端审计证据。
- **INT-002**:完成=Registry 结构、UNKNOWN/UNVERIFIED 语义、fail-closed 消费、
  出处事件;未完成=任何已验证平台能力事实;保留=WP-P0-005/006;仍缺=官方一手
  证据与 contract test。
- **INT-003**:完成=不透明引用契约与全面禁止面;未完成=真实 Secret Manager
  检索;保留=OQ-006 后的 Credential 运行时 WP;仍缺=真实解析路径的无泄漏证据。
- **ADM-002**:完成=Flag/Capability 元数据与写门不可覆盖不变量;未完成=Job
  Schedule/Backfill(WP-P0-003)与真实能力填充(平台 WP);仍缺=对应 WP 证据。

**三个主张分离**(11+1 标准):本设计通过实施后,WP-P0-002 可达其阶段
project-grade;Phase 0 未完成;整个产品不是 production-ready。
Traceability 状态保持 `PLANNED`;任何 PARTIAL 行不因 WP-P0-002 完成而变 `VERIFIED`。

---

## 25. Open Questions / Decision Requests / Unknowns(开放问题、决策请求与未知项)

### 25.1 既有 OQ 处置(保留 live 处置,不改动)

| OQ | 当前处置 | 不阻塞 | 阻塞的后续 Gate | 最小未来答案/证据 |
| --- | --- | --- | --- | --- |
| OQ-101 | OPEN;拓扑输入已足够本设计 | 元数据 Design/实施 | 真实 onboarding/当前 WP 业务验收、平台 WP | 实际 Ozon/WB 账户、店铺、仓库与履约清单(Owner 提供;经 ADM-001 面录入;合成清单不闭合真实 onboarding) |
| OQ-005 | OPEN | 本元数据设计 | 运行时 IAM 认证/执行 Design 与实施 | Owner+Controller 选定认证方案 |
| OQ-006 | OPEN | 不透明引用元数据设计 | 真实 Secret 检索、Raw/存储集成 | Secret Manager 与对象存储选型授权 |
| OQ-102 | OPEN | Registry 结构与 UNKNOWN/UNVERIFIED 语义 | 已验证 Capability Matrix 填充与行为 | 官方一手 API 角色/订阅/广告权限证据 + last_verified |

### 25.2 本设计新识别的未知项分类

**已按生产最佳实践自主决策(工程选择,不上报)** ——每项唯一决策与理由在正文:
审计所有权与同事务语义(§14);复合 FK 组织隔离(§17.2);Exclusion Constraint +
btree_gist(§7.2,V-101);UUID 身份与不可变 code(§5.1);无批量命令、无独立
幂等键存储(§16.5);EXPIRED 作派生态(§10.3);维护面 HTTP + 写开关 + 归因
(§15);`marketplaceintegration` 不依赖 `identityaccess`(§3.2);双时态省略
(§7.3);错误码扩展入 `shared.ErrorCode`(§16.4);Secret 解析端口不落代码
(§11.6);平台身份参考归 `organizationaccount`/`core`(§4.1);审计行哈希整体
归属审计强化 WP(§14.1);凭据轮换以 `replaces_credential_id` 链表达、无平行
状态列(§11.4);显式 `scope_mode` 凭据范围契约(§11.1a);凭据用途参考表 +
Store 范围子表(§11.1);能力主体柔性状态 + 平台权限要求证据表(§12.5/§12.5a);
Registry 同平台复合 FK(§17.2);Capability 不持久化到内部权限的映射(§12.5a);
allowed-source 两态无删除生命周期(§10.2)。

**真正的 Owner/凭据/法务/生产权威决策请求:本轮为零。** 无 BLOCKED 项;
本设计不含任何以 Owner 决策为前提的空洞。

**记录在案的外部核验依赖(不阻塞设计裁定)**:

| # | 项 | 处置 |
| --- | --- | --- |
| U-201 | native key 的平台侧结构语义 | 保持不透明文本;WP-P0-005/006 证据后如需规范化,以增量列+映射表处理 |
| U-202 | JDK tzdata 与 ISO-4217 清单随运行时版本演进 | 校验以运行时 JDK 为权威;实施时在测试中固定断言样本集(不断言全集) |
| U-203 | `btree_gist` trusted 状态在未来 PG 大版本的延续 | 实施日复核该文档页;若变化,回退方案=迁移改由属主角色显式安装(不改设计语义) |

---

## 26. File-level future implementation map(文件级未来实施图——计划,非实施)

### 26.1 阶段划分(单分支、单 Draft PR 内的有序提交)

| 阶段 | 内容 | 主要新文件(代表) |
| --- | --- | --- |
| C1 | `shared` 扩展:ErrorCode 增量、`MaintenanceWriteGate`+属性、`ProductionWritePolicy`(@AssertFalse)、`IdGenerator`;基线/profile yaml 两个新属性;单元测试 | `shared/{MaintenanceWriteGate,ProductionWritePolicy,IdGenerator}.java`、`shared/internal/config/{MetadataMaintenanceProperties,ProductionWriteProperties}.java`、`ErrorCode.java`(修改)、`application*.yaml`(修改)、`shared/**Test.java` |
| C2 | 审计能力 + V0002/V0003:`adminobservability.audit` named interface、内部仓储、迁移、append-only IT | `adminobservability/audit/{MetadataAuditRecorder,MetadataAuditQueries,MetadataAuditEntry,AuditAction,AuditActorType,AuditEntityType,AuditEventFilter}.java`、`adminobservability/internal/audit/**`、`db/migration/V0002__enable_btree_gist_extension.sql`、`V0003__create_metadata_audit_event.sql`、`database/MetadataAuditPrivilegeIT.java` |
| C3 | `organizationaccount` 全量 + V0004:**平台身份参考(`core.marketplace_platform`)**、domain/application/infrastructure/web、目录 API(含平台参考查询)、状态机、区间、时区/币种;评估既有 `allowEmptyShould` 放宽的收回(`..domain..`/`..application..` 首次出现真实主体) | `organizationaccount/{OrganizationDirectory,MarketplacePlatformRef,*Ref}.java`、`organizationaccount/internal/{domain,application,infrastructure/jdbc,web}/**`、`V0004__create_core_organization_metadata.sql`、`organizationaccount/**Test.java`、`database/OrganizationMetadataIT.java` |
| C4 | `identityaccess` 全量 + V0005:权限/范围/Service Account(含 allowed-source 生命周期)/授予 | `identityaccess/{AccessMetadataDirectory}.java`、`identityaccess/internal/**`、`V0005__create_iam_access_metadata.sql`、对应测试 |
| C5 | `marketplaceintegration` 全量 + V0006:凭据用途/凭据(显式 scope_mode)/Store 范围、轮换链、Registry(同平台复合 FK)、能力主体状态与平台权限要求证据、验证事件、Flag | `marketplaceintegration/{CapabilityDirectory,FeatureFlagDirectory}.java`、`marketplaceintegration/internal/**`、`V0006__create_platform_registry_metadata.sql`、对应测试 |
| C6 | ADM-001 面硬化:CORS 收窄至 `/api/v1/meta/**`、严格绑定配置、admin 契约/绑定/归因/开关全套 IT、log-capture 红线测试 | `shared/internal/config/WebConfig.java`(修改)、`*/internal/web/**`(契约测试)、`ObservabilityContractTest.java` |
| C7 | 双路径迁移测试(干净/升级)、schema 终态比较、grants 全矩阵负向、架构/Modulith 回归、覆盖率补齐 | `database/{MetadataMigrationPathIT,MetadataPrivilegeMatrixIT}.java` 等 |
| C8 | 文档/追溯/证据:runbook 维护操作页、`traceability.csv` 更新(状态仍 PLANNED,补 design_record/code_location/test_case)、`docs/07-phase-evidence/WP-P0-002/**`、`CURRENT_STATE.md`(按届时 Gate 指令)、README 增量 | `docs/06-runbooks/metadata-maintenance.md`、`docs/07-phase-evidence/WP-P0-002/**`、`docs/01-requirements/traceability.csv`(修改) |

设计副本落地路径(实施 C8):
`docs/02-architecture/designs/WP-P0-002-organization-store-warehouse-credential-metadata-design.md`
——**单一稳定文件名**;不在 `docs/02-architecture/designs` 创建任何带版本后缀
的平行副本;落库副本与本文档一样只含当前功能行为(修订沿革仅在文档控制
元数据块)。

### 26.2 本地验证命令(每阶段末)

```bash
./mvnw -B -ntp -DskipITs verify                 # 单元+架构(surefire)+覆盖率
./mvnw -B -ntp verify                           # + 全部 IT(Testcontainers postgres:18.4)
./mvnw -B -ntp -Dtest='*ArchitectureTest' test  # 架构边界单独复跑
python3 scripts/validate_governance.py          # 治理静态校验
python3 scripts/validate_production_readiness.py # 三条全局硬规则
```

### 26.3 CI/Gate 覆盖

既有 11 个稳定检查名**不增不改**:新测试自然进入 `backend-build` /
`architecture-boundary` / `backend-integration`;`governance` 覆盖新迁移与文档;
`security` 组覆盖新代码。无新 workflow、无新 Required Check、无 Node/TS 变更
(PR #6/#7 边界遵守)。

### 26.4 规模守卫

不创建:`marketplaceintegration.adapter.**`、任何 `raw/staging/ledger/mart` 对象、
任何前端文件、任何 Secret 解析实现、任何 Job/Worker。**不为缩小首次实施而推迟
上表任何一项**——C1–C8 合计即本 WP 全部范围。

---

## 27. Compromise / stale / naming checks(妥协、陈旧与命名三查)

### 27.1 Compromise Retirement Check(实施如何满足)

- 无临时旁路:维护写开关、生产写启动校验、fail-closed 评估从第一提交就是最终
  形态(本阶段语义下),不存在"先放行后收紧"的过渡实现;
- 无占位生产实现:不落无实现的端口接口(§11.6)、不建空包/空层、无 TODO 承载的
  行为空洞;`VERIFIED` 拒绝路径(§12.6)带显式清理触发器注释,属**有到期日的
  边界**而非占位;
- 无弱化测试:§23 矩阵在实施 PR 内一次交齐;负向测试与正向同批;
- 无陈旧并行状态:审计唯一表、错误码唯一注册表、无第二套维护通道;CORS 收窄
  替换而非并存旧配置。

### 27.2 Functional JavaDoc Rewrite Check

- 全部新 JavaDoc/注释按既有基础的风格描述**当前行为、不变量、失败/恢复语义与
  理由**(参照 `V0001`/`CorrelationId` 的注释范式);
- 无历史阶段叙事("本阶段暂时…后续会…"不进源码;阶段边界只存在于治理文档与
  本设计)、无语法复述注释;迁移 SQL 注释解释每条 GRANT 对应的不变量;
- 实施完成前以 `validate_production_readiness.py`(TC-GLOBAL-002)与人工 review
  checklist 复核。

### 27.3 Production Naming Check

- 全部生产名描述领域职责(表名/类名/包名见 §4、§26,均无 `temp/demo/stub/fake/
  sample/vibe/scratch`);
- 测试专用命名保留在测试树(`testfixture/**` 既有约定;新 fixture 前缀
  `secret-ref://test/...` 仅存在于测试资源);
- `validate_production_readiness.py`(TC-GLOBAL-003)覆盖全部新增生产文件。

**本节不声称任何校验器已通过**;以上是实施使其通过的具体方式。

---

```text
DESIGN_SUBMISSION_STATUS: READY_FOR_CONTROLLER_REVIEW
WORK_PACKAGE: WP-P0-002
DESIGN_REVISION: 1.2
REPLACES: v1.1 (beddf819…f84667) / v1.0 (7a58df85…3356e0)
SOURCE_MAIN_SHA: 3c4f6a6210db377b5471d6014da6afd5bfef6127
SOURCE_MAIN_TREE: dd505f02539ddd755c805ac7d0793c4a77963eed
STATE_DELTA: NONE
REPOSITORY_MUTATION: NONE
IMPLEMENTATION_STATUS: NOT_STARTED
IMPLEMENTATION_AUTHORIZATION: NONE
PRODUCTION_WRITE_ENABLED: false
SECRET_OR_PII_USED: NONE
DEFERRED_ITEMS_IN_WP_SCOPE: NONE
OWNER_DECISION_REQUESTS: NONE
REQUESTED_NEXT_VERDICT: CONTROLLER_DESIGN_REVIEW
```

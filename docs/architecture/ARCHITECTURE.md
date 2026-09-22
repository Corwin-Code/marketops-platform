# MarketOps 架构概览

Modular Monolith 后端 + React SPA + PostgreSQL。异步工作用 PostgreSQL Task/Outbox + worker，不用 Kafka、Kubernetes 或微服务。

```text
marketops-console ──HTTP──▶ marketops-server ──JDBC──▶ PostgreSQL (8 schemas)
                                               └─▶ Raw ObjectStorage (FS/S3)
```

## 技术栈

- **后端**：Java 21、Spring Boot 4.1.0、Spring Modulith 2.1.0、Maven 3.9 wrapper。
  - Spring JDBC（`JdbcClient`）+ Flyway，无 ORM。
  - OIDC JWT 通过 oauth2-resource-server 校验；出站 HTTP 用 HttpClient 5。
  - 自动化测试代码已于 2026-09-22 移除，待新开发链重新规划。
- **数据库**：PostgreSQL 18（本地镜像 `postgres:18.4`）+ `btree_gist` 扩展。
- **前端**：Node 24、React 19.2、TypeScript 6.0、Vite 8、ESLint + Prettier。

## 模块划分

模块是 `com.mimococo.marketops` 的直接子包：
- `internal` 子包私有，对外只暴露模块根包和 `@NamedInterface` 包；
- 模块边界目前靠约定维护（原 Modulith `verify()` / ArchUnit 边界测试已随测试代码移除）；
- 禁止跨模块访问 Repository，每类数据只有一个写入方。

| 模块 | 职责 |
| --- | --- |
| `shared` | `Money`、`CorrelationId`、`ErrorCode`、`ProductionWritePolicy`、`SecretMaterialGuard`；`shared.port`（`OutboundHttp`、`SecretResolverPort`） |
| `adminobservability` | 健康检查/meta status；append-only 审计唯一所有者 |
| `organizationaccount` | Organization / Legal Entity / Marketplace Account / Store / Warehouse、时区与币种 |
| `identityaccess` | OIDC 用户、业务角色、scope 授权、Service Account |
| `marketplaceintegration` | Credential 引用、Capability/Endpoint Registry、Feature Flag、Kill Switch、采集与 Raw 保管、price / ad-bid / listing-description Command 和 readback |
| `productlisting` | Product / Variant / Barcode、平台 Listing、映射与冲突 |
| `operatingfacts` | 标准化、手工录入和文件导入得到的 canonical 事实 |
| `analyticsdecision` | 版本化 Metric、Confidence、Contribution Profit、确定性诊断规则 |
| `aicopilot` | AI 投影、`ModelGatewayPort`、输出校验 |
| `operationsworkflow` | Recommendation、WorkTask、Approval、CommercialPolicy、`GuardrailEngine`、Pilot allowlist |
| `availabilityrisk` | 缺货与库存覆盖风险 |
| `advertisingefficiency` | 广告效率 case 与 bid 建议 |
| `listingconversion` | Listing 转化、描述修改与促销动作、效果评估 |

## 共享主干

每项能力只有一个所有者，各业务域共用：

| 能力 | 所有者 |
| --- | --- |
| 组织/店铺拓扑 | `organizationaccount` |
| 身份授权 | `identityaccess` |
| 采集 job（含 lease/fence/cursor）与不可变 Raw | `marketplaceintegration`（通过 `ObjectStoragePort`） |
| Ledger 事实 | `operatingfacts` |
| Metric | `analyticsdecision` |
| Recommendation / Task / Approval / Policy | `operationsworkflow` |
| Command / Outbox / Readback | `marketplaceintegration`，由 `operationsworkflow` 授权发起 |
| 审计 | `adminobservability` |

主干保证：
- 过期的 fence 不能写入 cursor、Raw 或 Command；
- replay 不会触发平台下载；
- Raw 字节持久化并校验 hash 之后，采集进度才推进。

## 数据原则

- **Raw 不可变**：平台响应、报表、事件和导入都存入 `raw.*`，附带 hash、schema 版本和来源时间。采集与 replay 均幂等。
- **Ledger 只追加**：`ledger.*` 只追加不覆盖。更正通过冲销、调整或新计算版本完成。`mart.*` 可重建，不作审计依据。
- **缺失和置信度都是值**：每个数值都附带 `ValueState`（`AVAILABLE` / `NOT_AVAILABLE` / `UNDEFINED`）和 `ConfidenceState`（`CANONICAL_CONFIRMED`、`CANONICAL_PENDING_SETTLEMENT`、`ESTIMATED_EXPLAINED`、`STALE`、`INCOMPLETE`、`CONFLICTED`、`UNKNOWN`）。
  - 只有 `CANONICAL_CONFIRMED` 可以支撑写入，其他情况以 `REQUIRED_METRIC_UNAVAILABLE` / `METRIC_CONFIDENCE_INSUFFICIENT` 拒绝。
  - 缺失值不当作 0。
- **平台事实记录在数据行里**：`platform.platform_api_profile`、`platform_endpoint`、`capability_operation`、`staging.normalization_mapping`、`ops.ai_provider` 等表的每一行都带 `verification_state` 和出处。
  - 只有 `VERIFIED` 且 `ACTIVE` 的行可用，否则无法发起调用。
  - 平台改动时由运维人员更新记录，不需要发版。
- **金额用 decimal**：`shared.Money(BigDecimal, currencyCode)`，scale 4，HALF_UP，币种必填。金额前后端都按文本传递。
- Buyer PII 和 Secret 不进入 AI、Mart、日志或前端 bundle。

## 写入安全链

```text
Recommendation → 确定性 Guardrail → Impact Preview → Approval / 有界 Policy
→ 幂等 Outbox Command → 官方 API → Readback → Audit + 结果跟踪
```

- **默认关闭**：`marketops.production-writes.enabled=false`，写入 worker 也默认关闭。
- **按 Capability 单独启用**：`PRICE_CHANGE`、`ADS_CHANGE`、`LISTING_CHANGE` 各自启用。Feature Flag、`ops.pilot_allowlist_entry` 和 Kill Switch（`ops.kill_switch_event`）可按 platform / account / store / capability / entity 限定范围。
- **状态不互相蕴含**：Recommendation 不等于授权，授权不等于平台成功，平台受理不等于 Readback 一致。结果未知时不能盲目重试。
- **数据库强制写入权限**：应用角色对 `ops.price_command` 只有 `SELECT`/`INSERT`，没有 `UPDATE`。状态变更走 `SECURITY DEFINER` 函数，允许的转移记录在 `ops.price_command_transition` 表中。`ad_bid_command` 和 `lc_description_command` 同理。数据库保证：
  - 不在允许列表中的转移被拒绝；
  - 需要 lease 的转移必须 fence 和 owner 都匹配；
  - 标记成功需要同一事务内观察到目标值的 readback；
  - 补偿需要 readback 仍能看到本 Command 写入的值；
  - 应用无法修改 `ops.policy_authorization.used_count`。

## AI 边界

- **输入**：canonical Fact/Metric → 已批准的 Projection（`ops.ai_projection_*`）→ 字段 allowlist → 排除 Secret/PII → 模板 → provider 中立 Gateway。Raw payload 和任意 SQL 结果都不能发给模型。
- **输出**：结构化的 `Fact` / `Inference` / `Recommendation` / `Unknown`，按 `diagnosis-output-v2.schema.json` 校验，拒绝未知字段。
  - AI `Fact` 只能复述已有的 canonical 事实。
  - 模型自行计算的结果标为 `AI_DERIVED_EXPLORATORY`，不能驱动高风险执行。
- **不掌握授权**：AI 不持有审批、幂等键、Outbox、Credential 或 Kill Switch，它的建议仍要经过确定性 Gate 和 Approval。
- **审计**：调用写入 `ops.ai_invocation` / `ai_output_claim` / `ai_claim_evidence`。provider 可以停用，停用后退回确定性路径。
- **现状**：`HttpModelGateway` 由 `ops.ai_provider` 中的记录驱动，不在迁移里预置 provider。已接入阿里云百炼 OpenAI 兼容接口（`qwen3.8-max`，关闭深度思考、JSON 模式），由 `make ai-provider` 经维护接口登记；key 通过 `secret-ref://ai/dashscope-api-key` 从 Secret 目录解析，出站域名受 `marketops.outbound.destinations` 白名单约束。本地步骤见 `docs/development.md`。

## 平台适配器

- **端口**：`marketplaceintegration.port`（`AcquisitionPort`、`PriceWritePort`、`AdBidWritePort`、`DescriptionWritePort`、`ObjectStoragePort`）。
- **HTTP 适配器**：`adapter.http` 中的 `PlatformHttp*Adapter` 是通用适配器，平台相关内容全部读取 Registry 中已记录的规格。
- **存储适配器**：`adapter.objectstorage` 提供 `FilesystemObjectStorage`（本地默认）和 `S3CompatibleObjectStorage`（SigV4）。
- **Secret**：通过 `SecretResolverPort` 解析，实现为 `MountedSecretResolver`。
- **vendor 隔离**：vendor DTO/SDK 只能放在 `adapter.<platform>`，不进入 domain 或模块 API。重试、配额、轮询都留在 adapter 内。
- **现状**：还没有 `adapter.ozon` / `adapter.wildberries`，也没有 `VERIFIED` 规格。**真实 Ozon/Wildberries API 尚未接入**，目前只用 synthetic fixture。

## 数据库与迁移

- **迁移**：Flyway 基线为依次执行的 4 个文件——`V0001` 结构、`V0002` 种子数据、`V0003` 索引/约束/触发器、`V0004` 授权（2026-09-22 由原 `V0001`–`V0124` 合并而成，结构、权限与种子数据等价），此后只能前向新增 `V0005` 起的迁移，已合入的迁移不能修改或重编号；Flyway 设置 `clean-disabled`、`validate-on-migrate`。生产环境的迁移执行方式（原 Yandex 托管迁移工具已移除）待实际部署时设计。
- **8 个 schema**：

  | schema | 内容 |
  | --- | --- |
  | `iam` | 用户、角色、scope |
  | `platform` | Registry、Credential 元数据 |
  | `raw` | 不可变 Raw |
  | `staging` | 导入与标准化 |
  | `core` | 业务实体与策略 |
  | `ledger` | 只追加的事实 |
  | `mart` | Metric、诊断、case 投影 |
  | `ops` | 任务、审批、Command、审计、AI |

- **角色**：
  - `marketops_migration`：执行迁移，拥有全部 schema；
  - `marketops_app`：运行时角色，只授予对象级权限，没有 `public` 权限，也没有默认权限。

## 前端

`frontend/marketops-console` 是单页应用，运行时依赖只有 `react` / `react-dom`。

| 目录 | 内容 |
| --- | --- |
| `session` | OIDC + PKCE 登录 |
| `health` | `HealthShell` |
| `queue` / `diagnosis` / `workflow` / `commands` | 优先级队列、诊断与 AI 解释、Recommendation 审阅、Command 时间线 |
| `availability` / `advertising` / `listing` | 业务域界面 |
| `state` | `ValueCell` 和 `confidence`，全应用唯一决定数值如何展示的模块，缺失值显示 "—" |

配置来自 `VITE_MARKETOPS_*`，前端不持有 credential。

## 本地运行架构

- **数据库**：`infra/compose/docker-compose.yml`（`marketops-local`）只运行一个 `postgres:18.4`。
  - 端口绑定 `127.0.0.1:${MARKETOPS_DB_PORT:-5432}`。
  - `postgres-init/01-init-roles.sh` 创建两个角色。
  - 密码由 `make env-init` 写入被忽略的 `.env.local`，缺少时 compose 报错退出。
- **后端**：监听 `127.0.0.1:8080`，profile 有 `local` / `ci` / `staging` / `production`。
  - Raw 默认写入 `./local-data/raw-custody`。
  - 所有 worker 默认关闭；actuator 只暴露 `health` / `info`。
- **部署**：生产目标为 Yandex Cloud `ru-central1`，使用托管 PostgreSQL、S3 兼容存储、Secret Manager 和 OIDC。部署基础设施暂时从仓库中移除。

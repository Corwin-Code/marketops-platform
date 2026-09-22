# MarketOps 平台命名建议

建议采用“三层名称”：

| 层级         | 推荐名称                                                  | 用途                           |
| ---------- | ----------------------------------------------------- | ---------------------------- |
| 正式中文名      | **俄罗斯 Marketplace 运营与决策平台**                           | 项目立项、需求文档、汇报、知识基线            |
| 正式英文名      | **Russia Marketplace Operations & Decision Platform** | 英文文档、架构说明、正式标题               |
| 产品短名 / 工程名 | **MarketOps Russia**，简称 **MarketOps**                 | 页面标题、Git 仓库、代码包、Docker Image |

上传的 Baseline 已经把正式名称固定为 **Russia Marketplace Operations & Decision Platform**，并明确它是一套内部 Marketplace Operations & Decision Platform，而不是单纯的 GMV Dashboard、ERP 或 WMS。 

因此，最合适的命名组合是：

```text
正式产品名：
俄罗斯 Marketplace 运营与决策平台

英文正式名：
Russia Marketplace Operations & Decision Platform

产品显示短名：
MarketOps Russia

工程短名：
marketops
```

---

# 一、为什么推荐 `MarketOps`

`MarketOps` 能同时覆盖：

* Marketplace Integration；
* Product / Listing；
* Order / Fulfillment；
* Inventory；
* Finance / Profit；
* Advertising / Promotion；
* Analytics / Decision；
* Recommendation / Task / Approval；
* AI Copilot。

它不会把产品错误地限制为：

* `Dashboard`：项目不只是数据看板；
* `BI`：还包括任务、审批和受控执行；
* `ERP`：首期不替代 ERP、WMS 或会计系统；
* `AI Platform`：AI 只是后续能力层，不是系统事实基础；
* `Ozon-WB-System`：代码不应与当前两个 Marketplace 永久绑定。

Baseline 本身要求通过 Adapter 支持 Ozon、Wildberries，并为未来扩展其他 Marketplace 保留边界；首版只是 Ozon-first、Wildberries Read-parallel。

所以：

> **产品层面可以带 Russia，代码核心层面建议只使用 `marketops`。**

俄罗斯区域、币种、语言和部署环境应作为配置、Tenant 或 Deployment 属性，而不是写死在所有 Java package 和 TypeScript package 中。

---

# 二、推荐的仓库名称

## 方案 A：Monorepo，推荐

建议主仓库名称：

```text
marketops-platform
```

目录结构：

```text
marketops-platform/
├── backend/
├── frontend/
├── infra/
├── fixtures/
├── scripts/
└── docs/
```

GitHub、GitLab 或本机目录：

```text
marketops-platform
```

项目 README 标题：

```markdown
# MarketOps Russia

Russia Marketplace Operations & Decision Platform
```

这是目前最适合个人 Vibe Coding 的方式。前后端、Flyway、Docker、测试 Fixture、知识文件和部署配置都能绑定到同一个 Git Commit，方便 GPT-5.6 Sol、Claude 和 Codex 做源码交叉核验。

---

## 方案 B：前后端拆仓库

后续确实需要拆分时，可以采用：

```text
marketops-server
marketops-console
marketops-infra
marketops-docs
```

不建议使用：

```text
marketops-backend
marketops-frontend
```

虽然这两个名字没有错误，但 `server` 和 `console` 更能表达产品职责：

* 后端不只有 REST API，还包括 Worker、Integration、Backfill、Replay、Ledger、Outbox；
* 前端不是普通官网，而是 Operations & Decision Console。

Baseline 的目标架构也把前端定位为 `Operations & Decision Console`，包含 Command Center、Workbench、Tasks、Approval、Audit 和 AI Briefing。

---

# 三、后端工程名称

## 推荐名称

```text
marketops-server
```

如果后端采用 Maven：

```xml
<groupId>com.mimococo.marketops</groupId>
<artifactId>marketops-server</artifactId>
<name>MarketOps Server</name>
```

Spring Boot：

```yaml
spring:
  application:
    name: marketops-server
```

Java 启动类：

```java
package com.mimococo.marketops;

public class MarketOpsApplication {
}
```

Docker Image：

```text
your-registry/marketops-server
```

数据库名称：

```text
marketops
```

数据库应用用户：

```text
marketops_app
```

数据库 Migration 用户：

```text
marketops_migration
```

生产环境部署实例可以带区域：

```text
marketops-ru-production
marketops-ru-staging
```

但代码 Artifact 本身保持：

```text
marketops-server
```

---

# 四、Java 根包名

## 推荐格式

```text
com.mimococo.marketops
```

例如，公司实际拥有域名：

```text
mimococo.ru
```

则 Java package 可以是：

```text
ru.mimococo.marketops
```

如果公司域名是：

```text
mimococo.com
```

则使用：

```text
com.mimococo.marketops
```

不建议在还未拥有对应域名时直接使用：

```text
com.marketops
com.russia
com.ozonwb
```

Java 根包最好来自你实际控制的互联网域名，避免未来包名所有权和品牌冲突。

---

# 五、后端模块包结构

Baseline 已固定 Modular Monolith，并给出了 14 个推荐业务模块。

建议 Java package 与模块保持清晰对应：

```text
com.mimococo.marketops
├── identityaccess
├── organizationaccount
├── marketplaceintegration
├── productlisting
├── orderfulfillment
├── inventory
├── returnquality
├── financeprofit
├── advertisingpromotion
├── analyticsdecision
├── experiment
├── operationsworkflow
├── aicopilot
└── adminobservability
```

Marketplace Adapter 放在 `marketplaceintegration` 内：

```text
com.mimococo.marketops.marketplaceintegration
├── application
├── domain
├── infrastructure
├── port
├── ozon
└── wildberries
```

更完整的例子：

```text
com.mimococo.marketops.marketplaceintegration
├── application
│   ├── command
│   ├── query
│   └── service
├── domain
│   ├── capability
│   ├── credential
│   ├── ingestion
│   ├── job
│   └── raw
├── port
│   ├── inbound
│   └── outbound
├── infrastructure
│   ├── persistence
│   ├── worker
│   ├── objectstorage
│   └── observability
└── adapter
    ├── ozon
    └── wildberries
```

这里建议使用：

```text
adapter.ozon
adapter.wildberries
```

而不要让根包直接变成：

```text
com.mimococo.ozon
com.mimococo.wildberries
```

因为 Ozon 和 Wildberries 是外部 Adapter，不是整个系统的领域核心。

---

# 六、Maven 多模块命名

如果采用 Maven Multi-module，可以这样命名：

```text
marketops-parent
marketops-application
marketops-bootstrap
marketops-shared-kernel

marketops-identity-access
marketops-organization-account
marketops-marketplace-integration
marketops-product-listing
marketops-order-fulfillment
marketops-inventory
marketops-return-quality
marketops-finance-profit
marketops-advertising-promotion
marketops-analytics-decision
marketops-experiment
marketops-operations-workflow
marketops-ai-copilot
marketops-admin-observability

marketops-adapter-ozon
marketops-adapter-wildberries

marketops-server
```

不过，个人 Vibe Coding 初期不建议一次创建太多 Maven 子模块。

更适合初期的是：

```text
marketops-server
```

内部通过 Java package、Spring Modulith 或 ArchUnit 保持模块边界。等系统和模块边界稳定后，再判断是否拆成 Maven modules。

这符合 Baseline 的原则：

> Simple but Production-grade，不提前引入无必要的复杂度，但模块边界、幂等、审计、安全和测试不能省略。

---

# 七、前端工程名称

## 推荐名称

```text
marketops-console
```

`package.json`：

```json
{
  "name": "@mimococo/marketops-console",
  "private": true,
  "version": "0.1.0"
}
```

页面标题：

```text
MarketOps Russia
```

副标题：

```text
Marketplace Operations & Decision Platform
```

Docker Image：

```text
your-registry/marketops-console
```

前端应用目录：

```text
frontend/
├── src/
├── public/
├── tests/
├── package.json
└── vite.config.ts
```

如果未来做 Monorepo Workspace：

```text
apps/
├── console/
└── api-mock/

packages/
├── api-client/
├── ui/
├── domain-types/
└── test-fixtures/
```

对应 npm 包名：

```text
@mimococo/marketops-console
@mimococo/marketops-api-client
@mimococo/marketops-ui
@mimococo/marketops-domain-types
```

初期只保留：

```text
@mimococo/marketops-console
```

即可，不要为了形式提前拆大量前端 package。

---

# 八、前端 Feature 目录命名

建议前端按业务能力而不是按技术组件堆放：

```text
src/
├── app/
├── shared/
├── features/
│   ├── identity-access/
│   ├── organization-account/
│   ├── integration-health/
│   ├── product-listing/
│   ├── order-fulfillment/
│   ├── inventory/
│   ├── returns-quality/
│   ├── finance-profit/
│   ├── advertising-promotion/
│   ├── analytics-decision/
│   ├── experiments/
│   ├── operations-workflow/
│   ├── ai-copilot/
│   └── admin-observability/
└── pages/
    ├── command-center/
    ├── sku-360/
    ├── warehouse-workbench/
    ├── finance-close/
    ├── advertising-control/
    ├── tasks/
    ├── approvals/
    └── audit/
```

这与 Baseline 中的 Daily Command Center、SKU 360、Warehouse Workbench、Finance Close、Advertising Control、Recommendation 和 Approval 页面边界一致。

---

# 九、API 和生成客户端名称

后端 API：

```text
MarketOps API
```

OpenAPI 文件：

```text
marketops-openapi.yaml
```

API Base Path：

```text
/api/v1
```

生成的 TypeScript Client：

```text
@mimococo/marketops-api-client
```

鉴权 Audience：

```text
marketops-api
```

服务间 Service Account：

```text
marketops-worker
marketops-backfill
marketops-reconciliation
```

但它们是不同运行身份，不一定需要成为独立代码仓库。

---

# 十、环境和配置前缀

推荐统一使用：

```text
MARKETOPS_
```

例如后端：

```text
MARKETOPS_DATABASE_URL
MARKETOPS_OBJECT_STORAGE_ENDPOINT
MARKETOPS_OZON_SECRET_REF
MARKETOPS_WB_SECRET_REF
MARKETOPS_WRITE_ENABLED
```

Vite 前端必须使用 `VITE_` 前缀：

```text
VITE_MARKETOPS_API_BASE_URL
VITE_MARKETOPS_ENVIRONMENT
VITE_MARKETOPS_BUILD_VERSION
```

Kubernetes、Docker Compose 或部署资源名称：

```text
marketops-server
marketops-console
marketops-postgres
marketops-object-storage
```

生产区域部署可以进一步加后缀：

```text
marketops-server-ru-prod
marketops-console-ru-prod
```

---

# 十一、不推荐的名称

## 1. `russia-marketplace-dashboard`

不推荐，因为它会把产品错误地限定成 Dashboard，无法表达 Ledger、Task、Approval、Controlled Write 和 Readback。

## 2. `ozon-wildberries-platform`

不推荐，因为代码会与两个具体平台永久耦合，不利于 Adapter 扩展。

## 3. `marketplace-ai-platform`

不推荐，因为 AI 不是系统的基础事实源，且第一阶段重点是 Raw、Core、Ledger、Data Quality 和人工决策闭环。

## 4. `ecommerce-erp`

不推荐，因为 Baseline 明确不把首版定位成 ERP、WMS 或会计系统。

## 5. `rmp`

不建议单独作为仓库和 package 名：

```text
rmp
```

它过短、含义不清，搜索和日志定位都比较困难。可以在文档编号中使用 `RMP`，但代码中优先使用：

```text
marketops
```

---

# 十二、最终建议直接采用的名称清单

```text
正式中文产品名：
俄罗斯 Marketplace 运营与决策平台

正式英文产品名：
Russia Marketplace Operations & Decision Platform

产品短名：
MarketOps Russia

工程短名：
marketops

主仓库：
marketops-platform

后端：
marketops-server

前端：
marketops-console

Java groupId：
com.<your-company>.marketops

Java root package：
com.<your-company>.marketops

Spring application name：
marketops-server

Frontend npm package：
@<your-company>/marketops-console

OpenAPI：
marketops-openapi.yaml

数据库：
marketops

Docker Images：
marketops-server
marketops-console

平台适配器：
marketops-adapter-ozon
marketops-adapter-wildberries
```

其中我最推荐固定为：

> **产品：MarketOps Russia**
> **仓库：`marketops-platform`**
> **后端：`marketops-server`**
> **前端：`marketops-console`**
> **Java 根包：`com.<company>.marketops`**

这一套既保留了俄罗斯业务定位，也不会让核心代码被 Ozon、Wildberries、Dashboard 或 AI 等当前阶段特征锁死。

# MarketOps Russia

**Russia Marketplace Operations & Decision Platform**

## Current runtime state

项目唯一的动态 runtime state 入口是
[`CURRENT_STATE.md`](docs/00-governance/CURRENT_STATE.md)。本 README 不复制活动
Work Package、Gate 或 authorization 值，避免形成第二事实源。

Owner Git 流程契约见
[`OWNER_GIT_WORKFLOW_GUIDE.md`](docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md)；
是否启用指导模式同样只以 `CURRENT_STATE.md` 为准。

## 项目定位

MarketOps Russia 是面向俄罗斯本地经营主体的内部 Marketplace Operations & Decision Platform。第一阶段先建立数据真相、经营可见性和可审计的人工决策闭环，而不是先做一次性 Dashboard、通用 SaaS、ERP/WMS 替代品或无人审批的平台自动化。

## 唯一事实源顺序

发生冲突时按以下顺序处理：

1. `docs/01-requirements/baseline-v1.0-cn.md` 中已固定的 Owner Decision 与全局硬规则；
2. 已接受的 ADR；
3. 已批准的 Work Package；
4. 当前代码、Migration、自动化测试与 CI Evidence；
5. `DECISION_LOG.md`、`CURRENT_STATE.md` 和 `traceability.csv`；
6. ChatGPT / Claude 对话仅作为工作过程，不是最终事实源。

## 已固定的核心边界

- 双平台架构，Ozon 端到端优先，Wildberries 首期 Read Integration 并行；
- Read First，Write Later；生产写能力默认关闭；
- Java 21 + Spring Boot + PostgreSQL + Flyway + React + TypeScript；
- Modular Monolith + PostgreSQL Task / Outbox Worker；
- Raw、Inventory Ledger、Financial Ledger 不可变；
- Variant / Color / Size / Purchase Batch 是必要经营粒度；
- 官方 API 是唯一允许的程序化平台接入方式；
- AI 只提供解释、建议和生产力支持，不持有平台 Credential 或直接写权限；
- Phase Gate 未通过不得扩大自动化。

## 本地启动路径

完整步骤见 [`docs/06-runbooks/local-development.md`](docs/06-runbooks/local-development.md)。
最短路径：

```bash
make doctor        # 报告缺失的前置条件，不改动主机
make bootstrap     # 生成两个被 Git 忽略的 .env.local，并再次报告前置条件
make up            # 启动 PostgreSQL 并等待其可用
make backend-run   # 以本地数据库启动后端
```

另开一个终端：

```bash
make frontend-install
make frontend-dev
```

控制台在 <http://127.0.0.1:5173>。后端与控制台都只绑定回环地址：本基座尚无鉴权，
两者都不得暴露到网络上。

数据库角色名 `marketops_migration` 与 `marketops_app` 是**已提交的常量**，不是机密；
只有三个数据库口令由生成器写入 `.env.local`，且在任何代码路径上都不会被打印。

## 验证

| 命令 | 覆盖范围 |
| --- | --- |
| `make governance` | 治理规则、三条生产就绪硬规则及其自身测试 |
| `make backend-test` | 编译、单元测试、架构规则、配置断言 |
| `make backend-arch` | 仅架构边界规则及其 8 个 Fixture |
| `make backend-verify` | 以上全部，另加 Testcontainers 数据库集成测试 |
| `make frontend-check` | Lint、格式、类型、覆盖率测试、构建 |
| `make supply-chain` | 生成两侧依赖与许可证清单（输出被 Git 忽略） |
| `make fresh-clone` | 克隆当前提交并在其中完整验证，证明不依赖本地残留状态 |
| `make verify` | 上述本地验证的全集 |

浏览器验收由 `npm run test:browser` 自动执行真实后端、数据库与 Chromium 路径；
人工检查清单仍可作为补充，见
[`docs/06-runbooks/browser-smoke.md`](docs/06-runbooks/browser-smoke.md)。

## 证据

WP-P0-001 的后端、前端、数据库、覆盖率、浏览器、供应链与本地配置 Gate 已真实执行；
`package-lock.json` 已提交，Maven 与 npm 均从锁定输入构建。完整命令、聚合结果及
Fresh Clone / GitHub PR 边界见
[`docs/07-phase-evidence/WP-P0-001/`](docs/07-phase-evidence/WP-P0-001/)。

## 目录

```text
marketops-platform/
├── backend/marketops-server/    # Spring Boot 后端；两个模块：shared、adminobservability
├── frontend/marketops-console/  # React 运维控制台；七种平台状态
├── infra/compose/               # 本地 PostgreSQL 与角色初始化脚本
├── fixtures/                    # 后续 Golden Dataset / 脱敏 Fixture
├── scripts/
├── docs/
│   ├── 00-governance/
│   ├── 01-requirements/
│   ├── 02-architecture/
│   ├── 03-work-items/
│   ├── 04-api/
│   ├── 05-testing/
│   ├── 06-runbooks/
│   ├── 07-phase-evidence/
│   └── 08-handoffs/
├── .github/
├── CLAUDE.md
└── AGENTS.md
```

## 当前禁止事项

- 不向聊天、Git、日志、Issue、PR 或普通配置提交任何 Ozon/WB Token、密码、Cookie、Buyer PII 或私钥；
- 不绕过活动 Work Package 的 Design Gate 直接开始实现；
- 不直接向 `main` 推送产品代码；
- 不把 Fixture 结果描述成真实平台 API 已接通；
- 不在 Phase 0 开启任何生产平台写能力；
- 不把未执行的检查写成已通过：未跑就是未跑。

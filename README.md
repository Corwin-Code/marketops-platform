# MarketOps Russia

**Russia Marketplace Operations & Decision Platform**

项目状态：`INITIATING`  
当前阶段：`Sprint 0 / Phase 0 — Data, Identity & Visibility Foundation`  
当前活动 Work Package：`WP-P0-001 — Repository, Governance & CI Foundation`  
当前授权：`DESIGN ONLY`；尚未授权产品代码实现。

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

## 启动路径

1. 阅读 [`START_HERE.md`](START_HERE.md)；
2. 运行本地治理检查：

```bash
python3 scripts/validate_governance.py
```

3. 初始化 Git 仓库：

```bash
bash scripts/bootstrap-repo.sh
```

4. 在 GitHub 创建私有仓库 `marketops-platform`，推送 `main`；
5. 按 `docs/00-governance/GITHUB_SETUP.md` 配置 Ruleset；
6. 在 Claude Project 中加载指定知识文件并执行 `docs/08-handoffs/CLAUDE-WP-P0-001-DESIGN-PROMPT.md`；
7. 将 Claude 的设计输出提交回总控窗口，等待明确的 Controller Verdict。

## 目录

```text
marketops-platform/
├── backend/                     # WP-P0-001 实现阶段创建
├── frontend/                    # WP-P0-001 实现阶段创建
├── infra/                       # WP-P0-001 实现阶段创建
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
- 不绕过 Design Gate 直接让 Claude 实现 WP-P0-001；
- 不直接向 `main` 推送产品代码；
- 不把 Fixture 结果描述成真实平台 API 已接通；
- 不在 Phase 0 开启任何生产平台写能力。

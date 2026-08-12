# START HERE — Session 0 启动操作

本启动包已经完成控制面初始化。远端 GitHub 仓库、账号连接和权限设置必须由 Owner 在自己的账户中完成。

Owner Git 流程指导模式当前为 `REQUIRED`。每次任务开始先阅读
`docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md`，由当前 Agent 结合实际
分支、PR 和 CI 状态讲解完整流程及下一步。只有 Owner 明确确认不再需要
辅助后才可关闭该模式。

## A. 先确认项目状态

打开：

- `docs/00-governance/CURRENT_STATE.md`
- `docs/00-governance/CONTROL_SESSION_0001.md`
- `docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md`

当前唯一授权是：

```text
AUTHORIZED_TO_START_WP-P0-001_DESIGN
```

这意味着 Claude 先设计，不得修改产品代码、创建 Migration 或提交 PR。

## B. 创建 GitHub Public 预生产仓库

建议仓库名：

```text
marketops-platform
```

建议设置：

- Visibility：Public（仅限预生产，见 D-15）；
- Default branch：`main`；
- 不自动添加 README、License 或 .gitignore，避免与本包冲突；
- 完成首次推送后再配置 Ruleset。

所有提交、Issue、PR、Review 和 Actions 日志都按公开信息处理。项目达到真实生产上线时，
或更早需要引入机密业务资料前，必须升级所需 GitHub 套餐、将仓库改回 Private，
并重新验证 Ruleset、Required Checks 和安全功能。

本地执行：

```bash
cd marketops-platform-bootstrap
bash scripts/bootstrap-repo.sh
git remote add origin <YOUR_REPOSITORY_REMOTE>
git push -u origin main
```

Windows PowerShell：

```powershell
Set-Location marketops-platform-bootstrap
./scripts/bootstrap-repo.ps1
git remote add origin <YOUR_REPOSITORY_REMOTE>
git push -u origin main
```

## C. 配置 GitHub Ruleset

严格按 `docs/00-governance/GITHUB_SETUP.md` 操作。首次至少要求：

- `main` 只能通过 Pull Request 更新；
- Required status check：`governance`；
- 禁止 force push；
- 禁止删除 `main`；
- 要求解决 PR conversation；
- 个人独立开发阶段不设置“必须 1 个他人批准”，否则 Owner 自己的 PR 会被锁死；
- Owner 仍只在 GPT Controller 给出 `APPROVE_FOR_HUMAN_MERGE` 后手工 Merge。

## D. 固定 ChatGPT Control Tower

当前 ChatGPT Project 继续作为唯一总控空间。将以下文件保留在 Project Sources 或上传到 Project：

- Baseline v1.0；
- Naming Baseline；
- `PROJECT_CHARTER.md`；
- `CURRENT_STATE.md`；
- `DECISION_LOG.md`；
- `OPEN_QUESTIONS.md`；
- `QUALITY_GATES.md`；
- 当前 Work Package 和相关 ADR。

将 `docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md` 复制到 Project Instructions。

## E. 创建 Claude Build Studio

新建 Claude Project：

```text
MarketOps Build Studio
```

加入 Project Knowledge：

- `docs/01-requirements/baseline-v1.0-cn.md`；
- `docs/00-governance/PROJECT_CHARTER.md`；
- `docs/00-governance/CURRENT_STATE.md`；
- `docs/00-governance/DECISION_LOG.md`；
- `docs/00-governance/QUALITY_GATES.md`；
- `docs/02-architecture/adr/`；
- 当前 Work Package。

把 `docs/00-governance/CLAUDE_PROJECT_INSTRUCTIONS.md` 设置为 Claude Project Instructions，并在 Claude Code 中连接刚创建的 GitHub 仓库。

## F. 启动第一个设计任务

把 `docs/08-handoffs/CLAUDE-WP-P0-001-DESIGN-PROMPT.md` 原样交给 Claude。Claude 的本轮输出只能是设计包，至少包括：

- 版本矩阵与官方核验日期；
- 仓库结构；
- 本地开发和 CI 命令；
- 后端、前端、数据库、Flyway、Docker 的初始化方案；
- 模块边界与架构测试方案；
- Secret / Config 方案；
- 测试、观测和回滚方案；
- 风险、假设和待 Owner 决策项。

## G. 回到总控窗口

Claude 设计完成后，将完整设计文本或仓库中的设计文件交回当前总控窗口。Controller 只会给出以下结论之一：

```text
APPROVED_FOR_IMPLEMENTATION
CHANGES_REQUIRED
BLOCKED_BY_OWNER_DECISION
BLOCKED_BY_EXTERNAL_CAPABILITY
```

没有 `APPROVED_FOR_IMPLEMENTATION`，不得进入编码。

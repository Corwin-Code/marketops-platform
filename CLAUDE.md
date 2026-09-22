# CLAUDE.md — MarketOps Russia 开发约定

本仓库以**产品开发交付为核心**。流程保持最小：没有治理文档、授权文件、证据包或多轮审查闭环。

## 先读

1. `docs/product/PRODUCT.md` — 产品定位、1.0 范围、全局业务规则、待决问题；
2. 与任务相关的 `docs/product/slices/slice-00x-*.md`；
3. `docs/architecture/ARCHITECTURE.md` — 模块、数据原则、写入安全链、AI 边界；
4. `docs/development.md` — 本地运行。

产品原始需求（中文原文）：`docs/product/requirements-baseline-cn.md`；命名约定：`docs/product/naming-baseline-cn.md`。

## 开发链

1. 从 `main` 开功能分支（`claude/<topic>` 或 `codex/<topic>`）。
2. 实现功能，保持改动聚焦在当前任务。
3. 本地确认能编译、能运行：`make backend-build`、`make frontend-build`，必要时 `make up` + `make backend-run` + `make frontend-dev` 手动走查。
4. 提交并推送分支，开 PR，写清：做了什么、如何手动验证、已知风险或未完成项。
5. Owner 审阅后合并。不需要额外的授权文件、证据目录或 Controller 往返。

**CI 与自动化测试目前暂停**，待 Owner 按新流程重新规划和授权；不得自行恢复工作流或引入重型验证。任何本地耗时超过约 15 分钟的验证，先说明成本并征得 Owner 同意。

## 必须遵守的产品规则

这些规则决定产品是否正确、安全，不属于流程：

- 不提交、不输出 Secret、平台 Credential、买家个人数据（姓名/电话/地址）或未脱敏生产数据；测试或演示数据一律合成。
- 真实平台写入（改价、改出价、改描述等）必须走完整安全链：证据化建议 → 确定性规则校验 → 人工审批（或 Owner 预授权策略）→ 幂等 Command → Readback → Audit → Kill Switch。写能力默认关闭，对真实账户启用任何写入前须 Owner 明确同意。
- 只使用 Ozon / Wildberries 官方 API 与报表；端点、字段、配额等平台事实以官方文档为准，并在代码或数据中记录来源与核验日期。
- 金额使用 decimal + 显式币种；Raw 不可变、Ledger 只追加；数据库迁移只向前：`V0001`–`V0004` 是合并后的基线（结构、种子数据、索引约束与触发器、授权），之后的结构变更一律新增迁移（`V0005` 起），不修改已合入 main 的迁移。
- vendor DTO / SDK 只放在 adapter 内；确定性 Metric 与规则是官方口径，AI 只负责解释、假设和建议，不能替代规则或执行写入。
- 优先 Ozon；Wildberries 的平台隔离与已有实现保留，但后续交付。

## 沟通

用中文回复 Owner；技术标识、字段、类名、命令和专业术语保留英文。如实报告做了什么、没做什么。

# AGENTS.md

所有编码 agent（Codex、Claude 等）遵循 [`CLAUDE.md`](CLAUDE.md) 中的开发链与产品规则，要点如下：

- 以产品开发交付为核心；从 `main` 开分支 → 实现 → 本地编译运行确认 → PR → Owner 合并。
- CI 与自动化测试暂停中，待 Owner 重新授权；不要自行恢复或新增重型验证。
- 不提交 Secret、Credential、买家个人数据；真实平台写入必须走完整安全链并经 Owner 明确同意。
- 只用 Ozon / Wildberries 官方 API；金额用 decimal + 币种；迁移只向前；vendor DTO 留在 adapter；AI 不替代确定性规则。
- 产品与架构说明见 `docs/product/` 与 `docs/architecture/`。

# MarketOps Russia

面向俄罗斯电商经营主体的内部运营与决策系统：统一 Ozon / Wildberries 官方数据与内部成本、库存事实，诊断 SKU 与店铺问题，给出具体动作，并在人工审批与安全控制下执行平台操作、回读验证。

**产品 1.0**：Slice 001–004，Ozon 优先，接入 Qwen，交给真实运营使用；Wildberries 与 Slice 005–007 后续交付。详见 [产品概览](docs/product/PRODUCT.md)。

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `backend/marketops-server` | Java 21 / Spring Boot 模块化单体，PostgreSQL + Flyway |
| `frontend/marketops-console` | React + TypeScript 运营控制台（Vite） |
| `infra/compose` | 本地 PostgreSQL |
| `scripts` | 本地配置生成（`make env-init`） |
| `docs/product` | 产品概览、各 Slice 产品说明、原始需求与命名约定 |
| `docs/architecture` | 架构概览 |
| `docs/api` | 已有 API 说明 |

## 快速开始

```bash
make bootstrap
```

```bash
make up
```

```bash
make backend-run
```

另开终端：

```bash
make frontend-install
```

```bash
make frontend-dev
```

完整说明见 [本地开发](docs/development.md)。开发约定见 [CLAUDE.md](CLAUDE.md)。

本仓库仅用于 MarketOps Russia 内部产品研发，不授予公开发布、再许可或对外分发权利。

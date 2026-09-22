# 本地开发

## 前置条件

| 工具 | 版本 |
| --- | --- |
| Java | 21 |
| Node / npm | 24.x / 11.x |
| 容器运行时 | 提供 `docker compose` 即可 |
| Python | 3（仅用于生成本地配置与自检） |
| GNU Make | 任意 |

先运行 `make doctor`：只报告缺什么，不改动主机。

## 首次运行

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

控制台地址 <http://127.0.0.1:5173>，后端 `127.0.0.1:8080`，两者都只绑定 loopback。控制台 API 需要有效的 OIDC bearer token；未配置 OIDC 时只有 health、build、metadata 等公开路径可用。

## `make bootstrap` 生成的文件

两份被 Git 忽略、仅本人可读的文件：

| 文件 | 内容 |
| --- | --- |
| `.env.local` | 三个随机生成的本地数据库密码与数据库端口 |
| `frontend/marketops-console/.env.local` | 后端地址与环境名 |

数据库角色名 `marketops_migration`、`marketops_app` 不是 Secret，固定写在配置中。

## 常用命令

| 命令 | 作用 |
| --- | --- |
| `make doctor` | 报告缺失的前置条件 |
| `make up` / `make down` | 启动 / 停止本地数据库（保留数据） |
| `make reset` | 删除本地数据库卷（需确认） |
| `make backend-run` | 以 `local` profile 启动后端 |
| `make backend-build` | 编译并打包后端 |
| `make frontend-dev` | 启动前端开发服务器 |
| `make frontend-build` | 前端类型检查并构建 |

## 说明

- `make down` 保留数据卷，下次 `make up` 时 schema 已迁移；`make reset` 后重新初始化并重跑角色脚本。修改 `infra/compose/postgres-init/sql/01-roles.sql` 只对新卷生效。
- 所有平台写入与后台 worker 默认关闭；本地只使用合成数据。
- 真实 Ozon / Wildberries API 与真实 LLM 尚未接入，接入方式见 `docs/architecture/ARCHITECTURE.md`。
- CI 与自动化测试目前暂停，待按新开发链重新规划。

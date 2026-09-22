# 本地开发

## 前置条件

| 工具 | 版本 |
| --- | --- |
| Java | 21 |
| Node / npm | 24.x / 11.x |
| 容器运行时 | 提供 `docker compose` 即可 |
| Python | 3（生成本地配置、登记模型服务） |
| GNU Make | 任意 |

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
| `make up` / `make down` | 启动 / 停止本地数据库（保留数据） |
| `make reset` | 删除本地数据库卷（需确认） |
| `make backend-run` | 以 `local` profile 启动后端 |
| `make backend-build` | 编译并打包后端 |
| `make frontend-dev` | 启动前端开发服务器 |
| `make frontend-build` | 前端类型检查并构建 |
| `make ai-provider` | 安装模型 key 并登记 Qwen 模型服务（需后端已启动） |

## 说明

- `make down` 保留数据卷，下次 `make up` 时 schema 已迁移；`make reset` 后重新初始化并重跑角色脚本。修改 `infra/compose/postgres-init/sql/01-roles.sql` 只对新卷生效。
- 所有平台写入与后台 worker 默认关闭；本地只使用合成数据。
- 真实 Ozon / Wildberries API 尚未接入；LLM 已接入 Qwen（阿里云百炼），本地启用方式见下一节。
- CI 与自动化测试目前暂停，待按新开发链重新规划。

## 接入 Qwen 模型（本地）

AI 解释与 Listing 辅助通过阿里云百炼的 OpenAI 兼容接口调用 `qwen3.8-max`。只在本机做三件事：

1. 在 `.env.local` 写入业务空间专属域名（不是 Secret）：

   ```
   MARKETOPS_AI_DASHSCOPE_HOST=<WorkspaceId>.cn-beijing.maas.aliyuncs.com
   ```

   不写时退回公共域名 `dashscope.aliyuncs.com`。专属域名只接受该业务空间的 API Key。

2. 把 API Key 保存在 `~/.marketops-platform/dashscope_api_key.txt`（仓库之外，仅本人可读）。

3. 启动后端后执行：

   ```bash
   make ai-provider
   ```

   它把 key 复制到 `~/.marketops-platform/secrets/ai/dashscope-api-key`（目录 700、文件 600，原子替换），再通过 loopback 维护接口登记 provider、记录调用规格并登记模型（写审计）。可重复执行；后端不在 8080 时加 `API=http://127.0.0.1:<端口>`。Secret 目录可用 `MARKETOPS_SECRET_MOUNT_DIRECTORY` 改到别处，脚本与后端读同一个值。

说明：

- 后端在每次调用时才从 Secret 目录读取 key，不写入数据库、日志或前端；key 文件末尾的一个换行会被忽略。
- 出站白名单（`application-local.yaml` 的 `marketops.outbound.destinations`）只放行该域名的 `/compatible-mode/v1/chat/completions`，单次调用上限 60 秒。
- 请求关闭深度思考（`enable_thinking: false`）并使用 JSON 模式，输出上限 2,400 token；实测一次诊断约 25–35 秒、1,250–1,650 个输出 token。模型输出中文（枚举值与代码保持英文，Listing 俄语草案保持俄语），仍须通过 `OutputValidator` 校验，不能替代确定性规则或触发写入。
- macOS 上 JDK 没有基于目录描述符的安全遍历，本地 profile 以 `workstation-fallback: true` 按路径读取 Secret，只接受属主独占的目录；非 local 环境开启该项会拒绝启动。
- 平台事实（接口、参数、模型能力）的出处与核验日期记录在 `scripts/register_ai_provider.py` 与 `ops.ai_provider` 的 `evidence_ref` / `last_verified_at` 中。
- 更换 key 后重新执行 `make ai-provider`。停用模型服务用维护接口 `POST /api/v1/admin/metadata/ai-providers/{id}/retirement`；停用后重跑脚本不会自动恢复，需要恢复时执行 `make ai-provider REACTIVATE=1`。
- 目前只登记一个模型（`qwen3.8-max`）。更换模型还不支持：网关取按字母序第一个启用的模型，而模型尚无停用接口。


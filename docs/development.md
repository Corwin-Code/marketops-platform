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
| `make ozon-probe` 等 | Ozon 试点店铺只读接入，见下文「接入 Ozon 试点店铺」 |

## 说明

- `make down` 保留数据卷，下次 `make up` 时 schema 已迁移；`make reset` 后重新初始化并重跑角色脚本。修改 `infra/compose/postgres-init/sql/01-roles.sql` 只对新卷生效。
- 所有平台写入与后台 worker 默认关闭；本地只使用合成数据。
- Ozon 只读接入处于第 0 步（API key 连通性），步骤见最后一节；Wildberries 尚未接入。LLM 已接入 Qwen（阿里云百炼），本地启用方式见下一节。
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


## 接入 Ozon 试点店铺（只读，第 0 步：连通性）

目标：让后端经完整的受控链路（注册表核验 → 采集任务 → 调用授权 → Raw 保管）成功调用一次 Ozon `POST /v1/roles`。这个接口只返回当前 key 的角色、可调用的方法和到期日，不含店铺经营数据。全程只读，后台 worker 保持关闭，由人手动触发。

平台事实（2026-09-28 核验）：官方文档 <https://docs.ozon.ru/api/seller/> 及其 OpenAPI 文件 <https://docs.ozon.ru/api/seller/swagger.json>（info.version 2.1）。请求发往 `api-seller.ozon.ru`，带 `Client-Id` 和 `Api-Key` 两个请求头；key 有效期 3 个月；`/v1/roles` 没有请求体。出处也记录在 `scripts/ozon_pilot.py` 开头。

后端不在 8080 端口时，下面每条 `make` 命令都加上 `API=http://127.0.0.1:<端口>`。

1. **准备 key**：在 Ozon 卖家后台「Настройки → Seller API」生成 key，**只勾选下面 4 个只读角色**：`Product read-only`（商品目录、价格、库存、描述）、`Report`（流量分析、财务交易、报表）、`Returns read-only`（退货）、`Actions read-only`（促销）。不要选 `Admin read only`：按官方文档核对，它含有切换定价策略状态、创建和删除 FBO 货位等写方法。然后把 key 和 Client ID 分别存成下面两个文件（目录 700、文件 600，不要放进仓库）：

   ```
   ~/.marketops-platform/secrets/ozon/pilot/seller-api-key
   ~/.marketops-platform/secrets/ozon/pilot/client-id
   ```

2. **探测**（只连 Ozon，不需要后端）：先在浏览器打开 <https://docs.ozon.ru/api/seller/swagger.json>，另存为 JSON 文件（例如 `~/Downloads/swagger.json`）。这一步要在浏览器里做，因为文档站会用反爬校验拦截脚本下载。然后运行：

   ```bash
   make ozon-probe OFFICIAL_SOURCE=~/Downloads/swagger.json
   ```

   脚本用 key 调用一次 `/v1/roles`，终端打印 key 的角色和到期日，不打印 key 本身。接着用官方文档里每个接口的说明，判断哪些角色能改数据。只要有这样的角色，就不记录证据：请删掉这个 key，只勾选只读角色重新生成，再探测一次。确认全部是只读角色后，响应原文和官方文档都会存进 `~/.marketops-platform/evidence/ozon/pilot/`（仅本人可读）。

3. **登记**（需要后端已启动）：

   ```bash
   make ozon-setup
   ```

   通过本机维护接口登记以下对象：试点账户和店铺（Client ID 写入账户的 `nativeAccountKey`）、READ 凭证（按 key 的实际到期日设置 `expiresAt`）、只读服务账号及其对账户的 READ 授权、连通性能力 `ozon-seller-connectivity` 和端点 `ozon-roles-v1`、采集任务 `ozon-pilot-roles`。可以重复运行，已存在的对象会跳过。

4. **准备第二个 Owner**：注册表核验要求一个 Owner 提交证据、另一个 Owner 审核，而且两人都要有 `KILL_SWITCH_OPERATE` 授权。本地做法：在 Keycloak 管理台新建一个用户，然后运行：

   ```bash
   make ozon-reviewer SUBJECT=<该用户的 Keycloak ID>
   ```

   这个做法只适用于本地开发；生产环境的审核人必须是另一位真人。

5. **核验**：

   ```bash
   make ozon-verify
   ```

   脚本会先后要求两位 Owner 在终端里登录，密码不会保存。它依次起草 Ozon 接口配置（profile）、两个认证头和端点，由第一位 Owner 提交探测证据，第二位 Owner 批准。证据最多有效 30 天，而且不会超过 key 的到期日；过期后要重新探测和核验。

6. **执行一次**：

   ```bash
   make ozon-run
   ```

   期望输出的运行状态为 `SUCCEEDED`、`pagesStored` 为 1。原始响应保存在 `local-data/raw-custody` 和 `raw.raw_acquisition_observation`。

说明：

- 出站白名单（`application-local.yaml` 的 `platform:OZON:read`）每个读取接口单独一条规则，目前放行 `/v1/roles`、`/v4/product/info/attributes`、`/v5/product/info/prices` 和 `/v4/product/info/stocks`。后续每接入一个读取接口，就单独加一条规则，并完成该接口的核验。
- 要立即停止读取，就用维护接口停用 READ 凭证（`POST /api/v1/admin/metadata/credentials/{id}/status`），或者暂停采集任务。
- 轮换 key：把新 key 存成新文件名，登记一个替换旧凭证的新凭证（`replacesCredentialId`），再停用旧凭证。
- 迁移 `V0007` 修复了采集链路此前对任何平台都无法成功的问题：调用授权只接受 `LEASED` 状态，而运行在第一次调用前已进入 `RUNNING`。

## Ozon 第 1 步：商品目录

目标：用 `POST /v4/product/info/attributes` 读完试点店铺的全部商品，存为 Raw，再标准化成 Listing 事实（`core.platform_listing` / `platform_listing_variant`），供后续商品映射使用。这些数据不含买家个人信息。

- **接口**：按 `last_id` 翻页，每页 100 个商品。官方文档没有写明列表怎么结束。2026-09-29 用真实账户探测发现：最后一页的记录数少于每页条数，但仍然带着 `last_id`；拿这个 `last_id` 再请求，会返回 HTTP 404 `{"code": 5, "message": "item not found"}`。所以端点登记为 `SHORT_PAGE_OR_NOT_FOUND`：遇到短页就结束；商品数正好是 100 的整数倍、最后一页是满页时，带游标的请求返回 404 也算结束。第一页返回 404 仍按失败处理。每次探测都会完整翻一遍，并记录实际遇到的结束方式；遇到短页时会再请求一次，确认后面确实没有数据。依赖迁移 `V0008`、`V0009`。
- **映射**：`id` → Listing 和变体的标识（Ozon 里一个商品就是一个变体），`offer_id` → SKU 键，`name` → 标题，`barcode` → 条码。
- **key 的角色**：需要 `Product read-only`。

步骤（后端需已启动；端口不是 8080 时加 `API=...`）：

```bash
make ozon-probe CAPABILITY=catalog OFFICIAL_SOURCE=~/Downloads/swagger.json
```

```bash
make ozon-setup CAPABILITY=catalog
```

```bash
make ozon-verify CAPABILITY=catalog
```

```bash
make ozon-run CAPABILITY=catalog
```

```bash
make ozon-normalize CAPABILITY=catalog
```

- 探测会先查一次 key 的角色，再完整翻一遍商品目录，每页原文存进证据目录。终端只打印页数、商品数和结束方式，不打印任何商品内容。
- 登记步骤会同时登记商品目录能力、端点、采集任务 `ozon-pilot-catalog`，以及 `OZON/LISTING` 标准化映射；映射以探测证据为依据完成核验。
- 核验和第 0 步一样，需要两位 Owner。接口配置和认证头已经核验过，这一步只起草新端点。
- 执行成功时，运行状态为 `SUCCEEDED`，`pagesStored` 等于页数（最后一页可能是空页）。标准化成功时，最后一行的 `lastReason` 为 `NOTHING_TO_PROCESS`。


## Ozon 第 2 步：价格与库存

目标：读取试点店铺每个商品的当前价格（`POST /v5/product/info/prices`）和各仓库类型下的库存（`POST /v4/product/info/stocks`），存为 Raw，再标准化成价格事实（`core.listing_price_observation`）和库存事实（`core.listing_stock_observation`）。这些数据不含买家个人信息。依赖迁移 `V0010`。

- **接口**：两个接口都按 `cursor` 翻页，每页 100 个商品。2026-09-29 用真实账户探测：最后一页的记录数少于每页条数，但仍然带着 `cursor`；拿这个 `cursor` 再请求，返回 HTTP 200、0 条记录、空 `cursor`，不会返回 404。因此两个端点都登记为 `SHORT_PAGE`，没有用 `SHORT_PAGE_OR_NOT_FOUND`：这两个接口从未出现过 404，如果把 404 当作结束，中途出错时数据会被悄悄截断。商品数正好是 100 的整数倍时，最后多出的那次请求返回空页，同样按短页结束。探测遇到短页后会再请求一次，确认返回的结果符合登记的规则。
- **价格映射**（官方字段含义）：`price`（不含促销的限价，也就是改价接口设置的值）→ `sellingPrice`；`old_price`（划线价）→ `listPrice`；`marketing_seller_price`（含卖家促销的限价，不含 Ozon 额外补贴）→ `discountPrice`；`currency_code` → 币种。三者都不是买家最终支付价。接口不返回价格生效时间，所以 `observedAt` 取这次响应的时间（`OBSERVATION_TIME`）。
- **库存映射**：每个商品下的 `stocks[]` 每个元素是一个仓库类型，一个元素产生一条事实（`childPointer = /stocks`）。商品标识从上层读取（`PARENT_POINTER /product_id`），`present` → 可售数量，`reserved` → 预留数量。`type` 通过登记的值映射转换：`fbo` → `MARKETPLACE_FULFILLED`，`fbs`/`rfbs` → `SELLER_FULFILLED`，`fbp` → `UNKNOWN`。映射里没有的仓库类型不会被猜测，这条记录会被拒绝。
- **探测检查**：只打印计数。价格检查币种，以及三种价格为 0 或缺失的数量；如果有商品没有 `price`，就不记录证据。库存按仓库类型统计条数；遇到值映射里没有的仓库类型，或者同一商品在同一履约方式下有多条库存（例如同时有 `fbs` 和 `rfbs`，或同一类型下按包装方式拆成多条），就不记录证据。因为一对商品和履约方式只保留一条事实，其余几条会被丢掉，这种情况要先决定是否合计。
- **目录不会被覆盖**：价格和库存记录只带商品标识。关联商品时，只在商品还不存在时插入；已有商品的标题、`offer_id`、条码保持不变。只有商品目录（`LISTING`）记录会更新这些字段。
- **key 的角色**：需要 `Product read-only`。

步骤（后端需已启动；端口不是 8080 时加 `API=...`；代人执行时加 `OPERATOR=<名字>`，审计里会记录这个操作人）：

```bash
make ozon-probe CAPABILITY=prices OFFICIAL_SOURCE=~/Downloads/swagger.json
```

```bash
make ozon-setup CAPABILITY=prices
```

```bash
make ozon-verify CAPABILITY=prices
```

```bash
make ozon-run CAPABILITY=prices
```

```bash
make ozon-normalize CAPABILITY=prices
```

库存把上面的 `CAPABILITY=prices` 换成 `CAPABILITY=stocks`，步骤相同。每次执行都会生成一份新的价格和库存快照；重复标准化同一份 Raw 不会产生重复事实。

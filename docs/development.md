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

- 出站白名单（`application-local.yaml` 的 `platform:OZON:read`）每个读取接口单独一条规则，目前放行 `/v1/roles`、`/v4/product/info/attributes`、`/v5/product/info/prices`、`/v4/product/info/stocks`、`/v1/analytics/data`、`/v3/product/info/list`、`/v1/product/rating-by-sku`、`/v1/analytics/product-queries`（这条同时覆盖 `/details`，因为一个调用只能命中一条规则，前缀不能重叠），以及 P7 的 `GET /v1/actions`、`POST /v2/actions/candidates`、`POST /v2/actions/products`。规则默认按路径段匹配前缀（`/a` 也匹配 `/a/b`）；下面挂着写方法的读取路径必须加 `exact-path: true` 只匹配路径本身，例如 `/v2/actions/products` 下面有写方法 `/v2/actions/products/deactivate`。后续每接入一个读取接口，就单独加一条规则，并完成该接口的核验。漏加规则时，运行会以 `request_could_not_be_built` 停在 `BLOCKED`。
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

## Ozon 第 3 步：流量分析（按天的下单件数）

目标：用 `POST /v1/analytics/data` 按 SKU 读取每个 UTC 日的分析数据，存为 Raw，再标准化成流量事实（`core.listing_traffic_observation`）。依赖迁移 `V0011`。

- **订阅限制**：按官方文档，没有订阅 Premium Plus 的卖家只能用 `revenue`（下单金额）和 `ordered_units`（下单件数）两个指标，只能查最近 3 个月，每天最多 50 次；所有卖家每分钟最多 1 次。2026-09-29 探测时确认试点店铺没有订阅 Premium Plus。而且请求无权使用的指标时，Ozon 不会报错，只是把这些指标悄悄丢掉：请求了 4 个指标，每行只回来 1 个值。如果按位置读取，下单件数会被当成曝光。所以这次只登记 `ordered_units`。将来订阅了 Premium Plus，要按新的指标集重新探测和核验。
- **一次运行读一个 UTC 日**：运行带时间窗口 `[当天 00:00Z, 次日 00:00Z)`，请求里的 `date_from` 和 `date_to` 都是这一天（模板占位符 `{windowStartUtcDate}`、`{windowEndUtcDate}`）。官方文档写明 "Seller API работает по UTC"，所以日期按 UTC 日理解。但这个接口本身没有说明分析数据按哪个时区切分日期，这一点是有记录的假设。
- **周期**：响应里不带日期，事实的 `periodStart` 和 `periodEnd` 取运行窗口（取值来源 `WINDOW_START`、`WINDOW_END`）。没有带窗口的运行不会调用接口，拒绝原因记为 `run_window_required`。
- **翻页**：响应里没有游标。端点登记为 `OFFSET` 加 `SHORT_PAGE`，不设游标指针：下一页的 offset 等于上一页加 100，遇到短页就结束。审批函数原来要求分页端点必须有游标指针，V0011 对"offset 或页码翻页、并且登记了短页规则"的端点放开了这一条。
- **SKU 对应**：分析数据按 Ozon `sku` 分组，而 listing 用的是 `product_id`。商品目录映射升级到 v2，多记录一个 `sku`（写入 `platform_listing_variant.native_item_key`）。流量记录用 `nativeItemKey` 代替 listing 键和变体键，标准化时通过目录反查。目录里没有的 SKU（例如已归档的商品）不会产生事实，也不会凭 SKU 新建商品。
- **key 的角色**：需要 `Report`。

步骤（后端需已启动；端口不是 8080 时加 `API=...`）：

```bash
make ozon-probe CAPABILITY=traffic OFFICIAL_SOURCE=~/Downloads/swagger.json
```

```bash
make ozon-setup CAPABILITY=traffic
```

目录映射升级到 v2（只需一次），然后重新采集并标准化目录，让每个变体记下自己的 `sku`：

```bash
make ozon-setup CAPABILITY=catalog SUPERSEDE=1
```

```bash
make ozon-run CAPABILITY=catalog
```

```bash
make ozon-normalize CAPABILITY=catalog
```

```bash
make ozon-verify CAPABILITY=traffic
```

```bash
make ozon-run CAPABILITY=traffic DAYS=7
```

```bash
make ozon-normalize CAPABILITY=traffic
```

- `ozon-run` 每天发起一次运行，两次运行之间间隔 90 秒。默认只跑昨天（1 天）；`DATE=YYYY-MM-DD` 指定最后一天，最多 30 天。探测时，短页之后的确认请求也会等 90 秒再发。
- **限流**：官方文档写的是每分钟 1 次。但 2026-09-29 补数时，两次调用间隔约 62 秒，仍然收到 HTTP 429 `{"code":8,"message":"You have reached request rate limit per second"}`；隔了 3.5 分钟重试，又收到一次同样的 429；第三次才成功。原因可能是同一个 Client-Id 的配额也被别的调用方占用了。所以调用间隔放宽到 90 秒。运行被限流后会进入 `RETRY_WAIT`，脚本会在原地最多重试 3 次。任务里有没跑完的运行时，下一次 `ozon-run` 会先把它跑完，再开新的运行；新接口 `GET /api/v1/admin/metadata/ingestion-jobs/{id}/live-run` 用来查这个运行；已经补完的窗口不会再重跑。
- **卡住的运行（`BLOCKED`）**：某一页无法判定结果时（未知状态、结构漂移、读不懂、配置无效），运行停在 `BLOCKED`，同一任务不能再开新的运行。查明原因后用 `make ozon-resolve` 处置（迁移 `V0016`，维护接口 `POST /api/v1/admin/metadata/ingestion-runs/{runId}/resolution`，原因写进审计）：
  - `RESOLUTION=retry`：原因已修复，运行转为 `RETRY_WAIT`，下一次 `ozon-run` 会先把它跑完；
  - `RESOLUTION=close`：放弃这次运行，转为 `FAILED_TERMINAL`（`CLOSED_BY_OPERATOR`）。

  ```bash
  make ozon-resolve CAPABILITY=queries RESOLUTION=retry REASON='补上了出站规则'
  ```
- 同一天重新采集时，新的 Raw 会被保存，但事实按"任务 + 商品 + 周期"去重，已有事实不会被更新，所以 Ozon 事后修正的数字不会进来。这是现有事实写入的通用规则。
- verify 核验成功后，脚本会在证据目录记下这次核验。之后用同一份证据再运行 verify 会直接跳过；确实需要重新提交时，加 `AGAIN=1`。

## Ozon 第 4a 步：价格竞争力（价格指数）

目标：把 Ozon 对每个商品价格竞争力的判断保存下来，用来诊断"为什么没有订单"。这一步不新增接口调用，数据就在每次价格采集的响应里（`price_indexes`）。依赖迁移 `V0012`。

- **映射**：价格映射升级到 v2，新增以下字段：
  - `price_indexes/color_index` → `priceIndexNative`：原样保存 Ozon 的等级词，取值 `WITHOUT_INDEX`、`SUPER`、`GREEN`、`YELLOW`、`RED`；
  - `ozon_index_data/min_price` 和 `min_price_currency` → Ozon 站内竞品的最低价和币种；
  - `external_index_data` → 其他平台竞品的最低价和币种。

  没有竞品价时，Ozon 返回的是 0 加空币种，这种情况按"没有竞品价"保存，不会当成价格 0。
- **可信度**：这些是平台分析数据，按需求基线属于 C 级，只用于诊断和趋势，不会驱动自动调价或利润计算（HR-07）。竞品是 Ozon 自己匹配的，可能包含相似但不完全相同的商品。
- **结果**：2026-09-29 试点店铺 41 个商品，`RED` 13 个、`YELLOW` 1 个、`WITHOUT_INDEX` 27 个，其他平台的竞品价格全部没有提供。RED 商品含卖家促销的价格，平均比 Ozon 站内竞品最低价贵 186%。

步骤（端点没有变化，不需要重新做两人核验；映射升级只需一次）：

```bash
make ozon-probe CAPABILITY=prices OFFICIAL_SOURCE=~/Downloads/swagger.json
```

```bash
make ozon-setup CAPABILITY=prices SUPERSEDE=1
```

```bash
make ozon-run CAPABILITY=prices
```

```bash
make ozon-normalize CAPABILITY=prices
```

## Ozon 第 4b / 4c 步：商品状态与内容评分

目标：继续补充诊断数据，回答"商品对买家是否可见"和"内容质量如何"。依赖迁移 `V0013`（按键分批请求）和 `V0014`（内容评分数据集）。

- **按键分批请求（V0013）**：`/v3/product/info/list` 和 `/v1/product/rating-by-sku` 不能翻页，只能按指定的商品来问。请求模板用 `{listingKeyBatch}`（目录里记下的 `product_id`）或 `{itemKeyBatch}`（记下的 SKU），由系统按固定顺序每次取 100 个，渲染成 JSON 字符串数组。端点登记为 `OFFSET` 加新的结束规则 `KEYS_EXHAUSTED`：所有键都问完才结束，不看每次返回了多少条，因为 Ozon 不认识的商品不会返回任何内容，但后面的键仍然要问。目录里一个键都没有时，不会发出请求（`no_keys_to_request`）。所以要先跑商品目录这一步。
- **商品状态（4b）**：`/v3/product/info/list` → LISTING_HEALTH。
  - `availabilities[0].availability` 映射为可售：`AVAILABLE` 算可售；`HIDDEN`（被隐藏）和 `UNAVAILABLE`（SKU 已删除）算不可售。
  - `statuses.status` 映射为状态词，第一条隐藏原因映射为 `blockedReasonNative`。
  - listing 健康、可用性风险、广告保护都读取这份可售状态。商品一旦被隐藏，listing 健康的必要条件就会失败。
- **内容评分（4c）**：`/v1/product/rating-by-sku` → 新数据集 `LISTING_CONTENT`，写入 `core.listing_content_observation`，保存 0–100 的评分，最多 4 位小数，不做四舍五入。
  - 评分按 SKU 返回，通过目录反查到商品。
  - 内容评分没有写进 `listing_health_observation`：有三个模块把那张表的最新一行当作可售状态，而评分没有可售信息，写进去会把状态覆盖成 `UNKNOWN`。
  - 评分背后的分组和未满足的条件暂时只保存在 Raw 里。
- **key 的角色**：需要 `Product read-only`。
- **2026-09-29 试点店铺结果**：41 个商品全部 `AVAILABLE`，没有隐藏原因，状态都是 `price_sent`，其中 11 个没有库存；内容评分全部在 75 分以上，平均 92.7，未满足的条件主要是媒体素材（每个商品约缺 3 项）。

步骤（先完成商品目录那一步）：

```bash
make ozon-probe CAPABILITY=status OFFICIAL_SOURCE=~/Downloads/swagger.json
```

```bash
make ozon-setup CAPABILITY=status
```

```bash
make ozon-verify CAPABILITY=status
```

```bash
make ozon-run CAPABILITY=status
```

```bash
make ozon-normalize CAPABILITY=status
```

内容评分把上面的 `CAPABILITY=status` 换成 `CAPABILITY=content`，步骤相同。

## Ozon 第 5 步：搜索需求与搜索词

目标：回答"有没有买家在找这些商品、用什么词找"。有搜索却没有下单，是店铺诊断要解释的核心问题。依赖迁移 `V0015`。

- **搜索汇总（`queries`）**：`/v1/analytics/product-queries` → 新数据集 `LISTING_SEARCH`，写入 `core.listing_search_observation`。
  - 每个 SKU 一行：统计期间内搜索过该商品的买家人数（`unique_search_users`），以及 Ozon 归到搜索的销售额（`gmv`，只和币种一起保存）。
  - 请求沿用 `{itemKeyBatch}`：每次 100 个 SKU，`KEYS_EXHAUSTED` 结束。
- **搜索词明细（`query-details`）**：`/v1/analytics/product-queries/details` → 新数据集 `LISTING_SEARCH_TERM`，写入 `core.listing_search_term_observation`。
  - 每个 SKU 取搜索人数最多的 5 个搜索词。每行保存搜索词原文、搜索人数、下单数和销售额；搜索词是事实键的一部分。
  - 这个接口一次接受全店 SKU（最多 1000 个），按从 0 开始的页码翻页，每页 100 行。所以新增两个请求占位符：
    - `{itemKeysAll}`：店铺全部 SKU，超过 1000 个时拒绝（`too_many_keys_to_request`），不会截断；
    - `{pageIndex}`：从 0 开始的页码。
  - 端点登记为 `PAGE` 加 `SHORT_PAGE`：不满 100 行的页就是最后一页。探测时已确认，最后一页之后再请求会返回 200 和 0 行。
- **窗口**：两个能力都按周运行（`window: WEEK`）。默认统计昨天往前 7 个 UTC 日；`DATE=<最后一天>` 可以指定。
  - 买家人数是去重统计，不能按天相加，所以不拆成按天的运行。
  - Ozon 免费版只能查最近一个月，不含当天。
- **Premium 字段**：排名位置、曝光人数和转化率需要 Premium 订阅，未订阅时返回 null，这些字段不映射。
- **证据等级**：搜索数据属于平台分析（C 级），只用于诊断和排序，不驱动调价。
- **key 的角色**：需要 `Report`。
- **2026-09-29 试点店铺探测结果**（2026-09-22 至 2026-09-28）：
  - 41 个 SKU 中有 30 个出现在搜索分析结果里，都有搜索人数；
  - 其余 11 个就是无库存、在搜索中不可见的商品；
  - 共有 55 个不同的搜索词，全部为 0 下单、0 销售额。

步骤（先完成商品目录那一步）：

```bash
make ozon-probe CAPABILITY=queries OFFICIAL_SOURCE=~/Downloads/swagger.json
```

```bash
make ozon-setup CAPABILITY=queries
```

```bash
make ozon-verify CAPABILITY=queries
```

```bash
make ozon-run CAPABILITY=queries
```

```bash
make ozon-normalize CAPABILITY=queries
```

搜索词把上面的 `CAPABILITY=queries` 换成 `CAPABILITY=query-details`，步骤相同。

## P2：内部商品、映射与成本

目标：把试点店铺的 Ozon 商品对应到内部商品（SKU），并把卖家在 Ozon 后台填写的成本价（`net_price`）采用为内部采购成本。SKU 诊断、缺货风险、Listing 辅助都要求商品先有映射；利润和保本价要求有成本。

- **成本价入库（V0017）**：价格映射升到 v3，`/v5/product/info/prices` 的 `price.net_price` 存为价格事实的 `seller_cost_price`（与价格同币种）。0 表示卖家没填，不保存。
- **生成内部商品（`make pilot-catalog`）**：
  - 读取最近一次商品目录探测，按标题分组，每个款式一个内部商品，每个货号一个变体。
  - 编码规则：变体编码取货号转小写，括号和其他不合法字符换成连字符（例如 `W-2643-D17(D22)-XL(2XL)` → `w-2643-d17-d22-xl-2xl`）；商品编码取同款货号的公共前缀。
  - 名称与标签：商品名取 Ozon 标题，变体名取原货号；尺码、颜色取卖家在 Ozon 填写的尺码（属性 9533）和颜色名（10097，缺失时取 10096）。
  - 条码：Ozon 条码（OZN 开头）登记到变体上，类型记为 `UNKNOWN`，匹配器会按条码自动提议映射。
  - 默认只生成草案（`~/.marketops-platform/evidence/ozon/<pilot>/internal-catalog-plan.json`），加 `APPLY=1` 才创建，可以重复执行。
  - 内部商品和变体建好后没有改名、停用接口，所以一定要先核对草案。
  - 维护接口新增 `GET /api/v1/admin/metadata/products?organizationId=&code=` 和 `GET /api/v1/admin/metadata/product-variants?organizationId=&skuCode=`，用来按编码查找。
- **控制台"商品映射与成本"**（`/store/master-data`，接口 `GET /api/v1/console/stores/{storeId}/master-data`，需要 `MAPPING_RESOLVE`）：
  - 每个平台商品显示映射状态（已映射、待确认、冲突、未匹配）、对应的内部商品和匹配方式、Ozon 成本价和当前采购成本。
  - 没有 `INTERNAL_FACT_INTAKE` 权限的人看不到成本。
  - "生成映射提议"调用 `POST /api/v1/console/mapping/stores/{storeId}/proposals`。
  - "确认选中的映射"逐条调用 `POST /api/v1/console/mapping/candidates/{id}/confirmation`：映射从确认时起生效，记录确认人、原因并写审计。
- **采用 Ozon 成本价**（`POST /api/v1/console/stores/{storeId}/marketplace-costs/adoption`，需要 `INTERNAL_FACT_INTAKE`）：
  - 为选中的已映射商品各新增一个采购成本版本（`PURCHASE`，诊断引擎只读这一种），金额取最新价格事实里的成本价，从 Ozon 给出该值的时间起生效。
  - 证据指向那次 Ozon 价格采集（`MARKETPLACE_RAW`），记录采用人和原因，并写审计。
  - 之前生效的成本在同一时间结束；已经一致的跳过。
  - 以下情况会拒绝整批：商品未映射、有冲突、成本价不是最新一次采集的，或者当前成本的生效时间晚于这次采集。
- **主数据自动规则（V0018，Owner 于 2026-09-29 授权）**：
  - 页面上的"启用自动规则"会写入一条 Owner 预授权策略（`ops.master_data_automation_policy`，每个店铺只有一条生效），需要 `MAPPING_RESOLVE` 和 `INTERNAL_FACT_INTAKE`；启用时立即运行一次。
  - **映射自动确认**：只处理无歧义的提议，即条码或货号完全一致、置信度 ≥ 0.90、该商品只有唯一提议、内部商品正常，且没有映射或提议给本店其他商品。较早一次生成提议时留下的"找不到对应内部商品"冲突不算阻碍，确认时会一并关闭；其他类型的冲突仍留给人处理。
  - **成本自动采用**：Ozon 成本价变化时自动采用。以下情况留给人确认，页面上标出原因：与当前成本相比变动超过阈值（默认 ±30%）、成本不低于买家价（含促销价，没有时用售价）、币种变化。
  - **何时运行**：启用时；商品目录（LISTING）或价格（PRICE）标准化记下新事实之后；`make pilot-catalog APPLY=1` 之后；维护接口 `POST /api/v1/admin/metadata/stores/{storeId}/master-data-automation/runs`。
  - **留痕**：确认人和采用人记为授权人，审计的操作者为 `master-data-automation`，映射和成本的原因里写明规则编号。
  - **停用**：用"停用"按钮（`POST /api/v1/console/stores/{storeId}/master-data-automation/retirement`），已完成的映射和成本保持不变。
- **Owner 决定（2026-09-29）**：
  - 按上述规则建内部商品，目前 4 个款式（20、9、6、6 个变体），以后新增款式时重新运行 `make pilot-catalog APPLY=1` 补建；
  - Ozon 成本价就是含所有杂费的单件总成本，数值以接口实际取到的为准；
  - 映射和成本不逐条手动确认，改为 Owner 一次授权的自动规则，成本变动阈值暂定 ±30%。
- **生效时间**：SKU 诊断的计算窗口按整点截止，映射和成本要在窗口截止前生效才会被算进去；刚确认的映射和成本，要到下一个整点之后的重算才会出现。

步骤：

```bash
make ozon-setup CAPABILITY=prices SUPERSEDE=1
```

```bash
make ozon-run CAPABILITY=prices
```

```bash
make ozon-normalize CAPABILITY=prices
```

```bash
make pilot-catalog
```

核对草案无误后：

```bash
make pilot-catalog APPLY=1
```

然后在控制台打开"店铺概览 → 商品映射与成本"，点"启用自动规则"（一次授权，之后自动运行）；也可以手动操作：先点"生成映射提议"，全选后点"确认选中的映射"，再筛选"成本待采用"，全选后点"采用选中的 Ozon 成本价"。

## P3：单件经济与诊断结论

目标：用确定性规则回答"为什么卖不动"，并算出每个商品按当前价卖一件能赚多少、价格降到多少会亏。

- **费率入库（V0019）**：价格映射升到 v4，`/v5/product/info/prices` 的以下字段存入价格事实：
  - 销售佣金百分比：`commissions.sales_percent_fbs`、`commissions.sales_percent_fbo`；
  - FBS 物流：`fbs_first_mile_min_amount` / `max_amount`、`fbs_direct_flow_trans_min_amount` / `max_amount`、`fbs_deliv_to_customer_amount`、`fbs_return_flow_amount`；
  - FBO 物流：`fbo_direct_flow_trans_min_amount` / `max_amount`、`fbo_deliv_to_customer_amount`、`fbo_return_flow_amount`；
  - 收单费 `acquiring`，税率 `price.vat`（小数，例如 `0.05`）。

  负数不保存；税率必须小于 1。
- **单件经济（`ListingUnitEconomics`，Owner 2026-09-29 定的口径）**：
  - 佣金取店铺履约方式对应的比例（店铺只有一种履约方式时用它，否则看该商品库存所在的履约方式）；
  - 物流费取最高档：FBS 为首公里、干线、末公里三项之和，FBO 为干线加末公里；收单费取接口给的上限；
  - 售价含增值税，按商品税率扣除 `P·v/(1+v)`，不计其他营业额税；
  - 成本取当前生效的采购成本（P2 由 Ozon 成本价采用而来，含所有杂费）。

  公式：`利润(P) = P − P·c − F − P·v/(1+v) − C`；保本价 `(F+C) / (1 − c − v/(1+v))`；目标利润价 `(F+C) / (1 − c − v/(1+v) − m)`，其中 `m` 是单件利润率下限。任何价格都覆盖不了成本时，保本价或目标利润价记为"无解"，不给数字。售价用买家价（有卖家促销价时用促销价）。金额精确到分：利润四舍五入，保本价和目标利润价向上取整，保证按显示的价格卖确实能达到；利润率保留四位小数。
- **新指标（指标定义 v2）**：`ORDERED_UNITS`、`SEARCH_USERS`（近 7 天内最新的一个统计期）、`LISTING_SELLABLE`、`CONTENT_RATING`、`PLATFORM_COMPETITOR_MIN_PRICE`，以及预估的 `PROJECTED_UNIT_PROFIT`、`PROJECTED_UNIT_MARGIN`、`PROJECTED_BREAK_EVEN_PRICE`、`TARGET_MARGIN_PRICE`。预估指标的置信度是 `ESTIMATED_EXPLAINED`，指标身份里记下履约方式、物流档位、税率、佣金率和利润率下限；缺售价、履约方式、费率或成本时为"不可用"，并记下缺的是哪一项，不按 0 计算。
- **新规则（诊断规则 v1）**：不依赖已实现利润，所以不受 `DATA_BLOCKED` 影响。

  | 规则 | 判断 |
  | --- | --- |
  | `LISTING_NOT_SELLABLE`（严重） | Ozon 状态为不可售 |
  | `DEMAND_NOT_CONVERTING` | 搜索人数 ≥ 需求下限，有库存，计算窗口内下单 0 |
  | `PRICE_GAP_REDUCIBLE` | 买家价高于 Ozon 竞品最低价，且目标利润价 ≤ 竞品最低价：降到竞品价仍能保住利润率下限 |
  | `PRICE_GAP_PARTIAL` | 买家价高于竞品最低价，保本价 ≤ 竞品最低价 < 目标利润价：能追平但守不住利润率下限 |
  | `PRICE_GAP_STRUCTURAL` | 买家价高于竞品最低价，保本价高于竞品最低价（或无解）：降价追平就会亏 |
  | `LOW_SEARCH_EXPOSURE` | 有库存、不是不可售，搜索人数低于曝光下限 |
  | `CONTENT_BELOW_TARGET` | 内容评分低于评分下限 |

  价差三条只在买家价和竞品价币种相同时判断，有价差时恰好触发其中一条。竞品价是 C 级平台分析，只用于诊断，不驱动调价。
- **阈值**（`application.yaml` 的 `marketops.analytics.thresholds`，Owner 2026-09-29 决定）：`minimum-unit-margin-rate: 0.15`、`demand-search-users-floor: 1000`、`low-exposure-search-users: 200`、`content-rating-floor: 0.90`。搜索数据每周统计、晚一两天出，所以单独设 `search-freshness: 9d`。
- **接口**：
  - `GET /api/v1/console/diagnosis/stores/{storeId}/listing-findings?window=D7`（`DIAGNOSTIC_VIEW`）：最近一次成功计算的各商品结论（带规则比较的数值）和单件经济指标，按规则汇总商品数；数字都是计算时存下的，接口不重新计算。
  - `POST /api/v1/console/diagnosis/stores/{storeId}/recalculation?window=D7`：重新计算，记录操作人；控制台为它单独设 60 秒超时（刚启动的后端算一次要十几秒）。
  - 存储语义：指标值和结论按输入去重，同一计算期内重复计算时，没有变化的值不写新行（`mart.metric_value.calculation_run_id` 是第一次写入它的那次计算）。所以一次计算的指标要经由 `mart.metric_value_evaluation` 读，结论取该计算期内每个商品每条规则最新的一行，不能按 `calculation_run_id` 过滤。
  - 店铺诊断接口的价格信号新增 `tariffs`（FBS 佣金、FBS 物流最高档合计、收单费、税率）。
- **页面（控制台首页）**：
  - 新增"诊断结论"：每条结论一张卡片，写明影响几个商品、是什么意思、下一步做什么；"查看商品"按该结论筛选下面的表格；右上角是计算所依据的数据区间和"重新计算诊断"按钮。
  - 商品表格新增"诊断"和"预估利润率"两列。
  - 详情抽屉顶部显示该商品的结论和单件经济（预估）：买家价、单件成本、预估单件利润和利润率、保本价、目标利润价、Ozon 竞品最低价和计算条件。
  - "无库存"直接由库存事实判断。V0024 起零销量商品不再被 `DATA_BLOCKED` 挡住，`STOCKOUT_RISK` 对零库存同样触发（`NO_PLATFORM_STOCK`），页面只显示一次（见下面"调校"）。
- **生效时间**：计算窗口截至上一个整点，刚采集的事实要到下一个整点之后的重算才会纳入。
- **不做**：不自动调价；结论不按影响大小排序（首页按固定顺序：先是阻止成交的，再是买家不买的原因）；搜索汇总里没出现的商品，搜索人数记为"不可用"，不当作 0。

步骤：

```bash
make ozon-setup CAPABILITY=prices SUPERSEDE=1
```

```bash
make ozon-run CAPABILITY=prices
```

```bash
make ozon-normalize CAPABILITY=prices
```

然后在控制台首页点"重新计算诊断"。

## P4：定时采集与自动重算

目标：按 Owner 的一次授权（2026-09-29 的决定），平台自己定时读取试点店铺的 Ozon 只读数据，采完自动标准化并重算诊断，不用每次手动跑 `make ozon-run` / `make ozon-normalize` / "重新计算诊断"。

- **授权（V0020）**：
  - `ops.scheduled_collection_policy`：每个店铺只有一条生效；记录授权人、时间和原因；停用时写版本号，并写审计。
  - 新权限动作 `DATA_COLLECTION_MANAGE`，只给 OWNER，需要近期验证过身份。
  - 现有 Owner 要补一条授权：`make owner-grant ACTION=DATA_COLLECTION_MANAGE`，可以重复执行。
- **计划**（全部按 UTC；每天一个采集时段，默认 `00:10`，即莫斯科 03:10、北京 08:10）：

  | 数据 | 频率 | 读什么 |
  | --- | --- | --- |
  | 商品目录、价格与费率、库存、商品状态、内容评分 | 每天 | 时段开始后成功读一次快照 |
  | 下单件数（`TRAFFIC`） | 每天 | 前天（UTC）整天；前 8 到前 2 天里没读过的天按从早到晚补读 |
  | 搜索汇总、搜索词 | 每周 | 结束已满 2 天的最近一个周一至周日，所以新的一周在周三读取 |

  事实先写入的算数，重读修正不了，所以按天、按周的数据都要等 Ozon 算完再读。无论定时还是手动，只要有一次运行成功读过某个目标，就不会再读。
- **执行**：定时器每分钟检查一次（`marketops.data-collection.interval`）。每轮按固定顺序处理每个店铺的采集任务，先读商品目录，因为其他数据要靠目录里的商品来对应：
  - 上一轮没做完的运行（排队中、等待重试，或者认领它的进程已经丢了）先接着做完；
  - 否则按计划建一个 `SCHEDULED` 运行，执行后立即标准化；新的目录或价格事实会触发主数据自动规则；
  - 被限流时进入等待重试，下一轮继续；下单分析接口每分钟只能调一次，两次运行之间至少隔 90 秒；
  - 同一目标失败后等 30 分钟再试，最多试 3 次，之后留给人处理；
  - 核验已过期的任务直接跳过，不会建出一个卡住的运行；
  - `BLOCKED` 的运行留给人用 `make ozon-resolve` 处理；
  - 每轮最多执行 4 个运行（`runs-per-pass`）。

  每次调用仍由数据库按已核验的接口、凭证、授权和核验证据逐次放行，定时运行和手动运行走的是同一条路径、同样的审计（操作者记为 `scheduled-collection`）。
- **重算**：一个店铺的采集告一段落后（这一轮没执行任何运行，也没有排队或等待重试的运行），满足下面任一条件就重算 7 天和 30 天两个窗口（触发类型 `SCHEDULED`）：
  - 最新一次计算没有包含新采集的数据，并且新数据早于当前整点；
  - 最新一次计算已经是一天前的了。

  计算窗口截至整点，所以 00:10 采集的数据在 01:00 之后重算；重算失败后等一小时再试。
- **记录**：`ops.scheduled_collection_event` 只追加，记下每个有变化的步骤：已采集（页数、记下的事实数）、等待重试、已卡住、失败、已跳过、标准化停止、已重算、重算失败。相同的跳过和卡住只记一次。
- **控制台**："店铺概览 → 数据采集"（`/store/collection`）：
  - 授权卡片：启用、停用；
  - 每个采集项的最近成功时间和窗口、当前运行状态、下一次计划、核验有效期（7 天内到期会提醒）、最近记录；
  - 7 天和 30 天窗口最近一次计算截至哪个整点；
  - 执行记录。

  接口：
  - `GET /api/v1/console/stores/{storeId}/data-collection`，需要 `DIAGNOSTIC_VIEW`；
  - `POST .../data-collection/policy`，需要 `DATA_COLLECTION_MANAGE`，请求体 `{reason}`；
  - `POST .../data-collection/policy/retirement`，需要 `DATA_COLLECTION_MANAGE`，请求体 `{reason, expectedVersion}`。
- **开关**：`marketops.data-collection.enabled` 在公共配置里默认关闭，本地配置打开；即使打开，也只采集在控制台启用了授权的店铺。
- **需要人处理的情况**：
  - 核验证据最长 30 天有效，到期后该项停止采集，需要重新 `make ozon-probe` 并由两位 Owner `make ozon-verify`；
  - `BLOCKED` 的运行要查明原因后 `make ozon-resolve`；
  - 标准化停止要查看原因（映射或数据问题）。
- **与手动采集的关系**：手动命令照常可用。定时器也可能执行手动命令刚排队的运行，由数据库的认领机制保证同一个运行只执行一次。

步骤：

```bash
make owner-grant ACTION=DATA_COLLECTION_MANAGE
```

然后在控制台打开"店铺概览 → 数据采集"，点"启用定时采集"并写明原因。

## P5：Qwen 店铺与商品解读

目标：在确定性的诊断结论之上，让 Qwen 用一句话说清"为什么卖不动、先做什么"。AI 只解释，不计算价格或利润，也不能批准或执行任何操作。

- **数据出境**（Owner 2026-09-29 决定第 1 项；`ProjectionEgress` 实现）：
  - 可以发送：
    - 商品标题、尺码、颜色；
    - 买家价和 Ozon 竞品最低价；
    - 件数（搜索人数、下单、库存、退货等）；
    - 比率（转化率、退货率、利润率、数据完整度、内容评分等）；
    - 诊断结论；
    - 最近一个统计期的前 5 个搜索词。
  - 不发送：销售额、广告花费、成本、费用、利润，以及由成本推出的价格（保本价、最低价、目标利润价、预估单件利润）。
  - 由成本推出的价格只换算成比例再发送：保本价比竞品价、保本价比现价、目标利润价比现价高多少，比如 `+73.21%`。比例配合 Ozon 公开的费率，理论上可以反推出成本的大致范围；决定允许发送比例，这一点在此说明。
  - 规则明细只发每条规则允许的字段。例如价差规则只发买家价、竞品价、溢价比例和利润率下限，不发保本价和目标利润价。
  - 不发买家信息和任何凭证。
  - 标题和搜索词属于平台上的文字：提示词写明它们只是数据，不是指令。
- **投影（V0021）**：
  - 停用 SKU 投影 v2 和 Listing 辅助 v1：它们会发送商品的全部指标，包括原始成本和利润金额。本地记录显示，历史上的 6 次 v2 调用都在 09-22、对象是合成店铺，试点店的真实成本没有出境过。
  - 新版本：
    - `SKU_GROWTH_PROFIT_DIAGNOSIS` v3：单个商品；
    - `LISTING_ASSISTANCE` v2：成员数据改用 v3 的内容；
    - `STORE_DIAGNOSIS` v1：店铺，内容是最近一次计算中每条结论覆盖几个商品（完整计数）和背后的编号（每条最多 5 个），以及按"严重程度 → 搜索人数"排出的前 5 个商品（只带价格比竞品、保本价比竞品两个比例）。投影太长、重复太多时，模型会开始重复字段名而不作答，所以店铺视图刻意保持精简。
  - 每个字段登记在 `ops.ai_projection_field`，新增两种分类：`MARKETPLACE_TEXT`（平台文字）和 `DERIVED_VALUE`（平台派生的比例或计数）。
  - 字段值写成可以直接引用的形式：百分比、带币种的价格、件数；内容评分是 100 分制。
- **输出校验**：沿用事实、推断、建议、未知四类结构和原有校验。诊断类解释（店铺、商品）新增"数字只能照抄"：陈述里的每个数字都必须出现在投影里（允许四舍五入到 0–2 位小数），0–10 的小整数、7 天、30 天等除外，不符合的陈述以 `DERIVED_CALCULATION_NOT_PRODUCTIZED` 拒绝。事实必须引用投影里的结论或指标编号；店铺没有自己的指标，所以店铺层面的计数改为引用它所统计的商品结论和指标。出现价格偏高（结构性）时，提示词禁止建议改价。
- **缓存与去重**：每次调用除了原有的请求摘要，还记录"内容摘要"（`ops.ai_invocation.content_digest`），计算时不含引用编号和计算期。同一对象、同一投影和提示词版本、同一窗口下内容摘要相同，就直接返回上次全部陈述通过校验的结果，标为"沿用"，不再调用模型（P7 起部分陈述被拒的结果不再沿用：一个格式错误的成员会让整组建议丢失，再次请求时重新调用模型）；同一对象已有请求在等待模型时，新的请求并入它。
- **每周一自动生成**（决定第 2 项）：定时采集在周一（UTC）采集告一段落、7 天窗口已算到当天采集之后，生成一次店铺周诊断，记为执行记录"已生成周诊断"；失败隔 3 小时重试。数据没变时沿用上一次的解读。
- **接口**：
  - `POST /api/v1/console/explanations/stores/{storeId}?window=D7`，需要 `DIAGNOSTIC_VIEW`：生成或沿用店铺解读；
  - `GET /api/v1/console/explanations/stores/{storeId}/latest?window=D7`，需要 `EVIDENCE_VIEW`：最近一次，不调用模型；
  - 商品解释接口不变，首页抽屉传 `window=D7`。
  - 返回中新增 `reused`。
  - 店铺级解释的读回权限按所属店铺判断。
  - 店铺结论接口的指标新增 `valueId`，页面用它把引用编号显示成"结论或指标 · 货号"。
- **页面**：
  - 首页在"诊断结论"上方新增"AI 周诊断"卡片：一句话结论（带 AI 置信度）、最多 3 个优先动作（带动作类型和预期效果）、依据（引用标签）、不确定项、生成时间和是否沿用；未通过校验的陈述折叠显示；
  - 商品抽屉在结论下方新增"AI 解读"。
  - 打开页面只读取已记录的结果，点"生成解读"才调用模型。
- **不做**：AI 不给价格或利润数字，不自动触发任何操作；结论按影响排序只用在投影里的商品顺序，首页结论卡片仍按固定顺序。

## P6：内容与搜索词优化

目标：回答"商品卡缺什么、怎么改能让更多买家搜到"。数据来自每天已在采集的商品目录和内容评分，不新增 Ozon 接口。AI 只生成俄语草稿供人审核，由人复制到卖家后台手工修改；平台写入保持关闭。

- **原始数据里已有的内容**（官方 OpenAPI 2026-09-29 核验）：
  - `/v4/product/info/attributes`（目录任务）：每个商品的描述类目、商品类型、图片和 `attributes[]`（属性编号和全部取值）。Ozon 用属性 4191 存描述（Аннотация），用 11254 存富内容。复合属性组（如视频）放在 `complex_attributes`，不读。
  - `/v1/product/rating-by-sku`（内容评分任务）：每个 SKU 的评分组，包括组评分、权重，每个条件（键、Ozon 的说明、是否满足、分值），以及 Ozon 点名要补的属性（`improve_attributes`，至少补 `improve_at_least` 项）。
- **标准化引擎扩展（V0022）**：
  - **伴随映射**（`staging.normalization_mapping.source_dataset_kind`）：读另一个数据集任务的同一份原始数据，与该数据集自己的映射在同一轮、同一游标下运行，把子记录写成自己数据集的事实。每个伴随映射在自己的事务里执行：它读不了或写不了某条原始数据时，只跳过它自己的这部分，并在处理结果里计入被拒记录，不会拖住目录、内容评分本身的事实。伴随映射读取的子记录路径不再算作主映射的字段漂移。
  - **两种新的字段来源**：
    - `EACH_POINTER`：数组里每个元素的某个值（一个属性的全部取值；一个评分组的每个条件）。只能写进声明为 `repeated` 的字段，元素缺值时保留空位，从同一数组读出的多个列表可以按位置对齐；
    - `ARRAY_LENGTH`：数组的元素个数（图片数），只用于整数字段。
- **新事实表**（只追加）：
  - `core.listing_catalog_observation`：每次目录快照每个商品一行，记录类目、类型、图片数和当时带有的属性编号。用来判断哪些属性是现在的：卖家删掉的属性不会被当成当前值；
  - `core.listing_attribute_observation`：属性值与该商品该属性最新一行不同时才写，恢复成旧值也算变化。描述标 `DESCRIPTION`，富内容标 `RICH_CONTENT`，这两个编号写在映射的取值表里，代码里没有 Ozon 编号。取值合计超过 131,072 字符时只记长度和摘要；
  - `core.listing_content_group_observation`：评分组变化时才写。
- **映射**（`scripts/ozon_pilot.py`，注册后都用探测证据核验）：
  - 目录映射 v3，新增观测时间、类目、类型、图片数和属性编号列表；
  - 伴随目录的 `LISTING_ATTRIBUTE` v1；
  - 伴随内容评分的 `LISTING_CONTENT_GROUP` v1。

  命令：

  ```bash
  make ozon-setup CAPABILITY=catalog SUPERSEDE=1 API=http://127.0.0.1:9999 OPERATOR=claude-for-owner
  make ozon-setup CAPABILITY=content API=http://127.0.0.1:9999 OPERATOR=claude-for-owner
  ```

  游标不回退，新映射只处理注册之后的原始数据：要么等下一次每日采集，要么手动 `make ozon-run` 加 `make ozon-normalize` 各跑一次目录和内容评分。
- **试点数据（2026-09-29）**：
  - 41 个商品都有描述（116–367 字符）和富内容（1,726–8,565 字符），图片 14–22 张，属性 26–29 个；
  - 评分组"图片与视频"和"文字描述"全部满分；"其他属性"21 个满分、20 个 50 分；
  - 这 20 个商品被 Ozon 点名补 Материал（材质）、Коллекция（系列）、Уход за вещами（洗护说明）中的至少 1 项；
  - 描述都不到 500 字符（"文字描述"组的一个条件没满足，但组内已满分，页面标为可选提升）。
- **类目属性接口**：
  - 探测 `POST /v1/description-category/attribute`，返回 403 `Api-Key is missing a required role for a method`：当前只读 key 的 4 个角色都不含 CategoryAPI 方法。
  - P6 不依赖它：已上架商品的必填属性必然已经填了，评分组也已点名要补哪些属性（带俄语名）。
  - 如果想看全部属性的中文名和必填标记，需要给 key 加一个包含 CategoryAPI 的角色，这属于 Owner 的决定。脚本里保留了只探测不登记的 `category-attributes` 能力。
- **没有写入 `lc_*` 观测**：
  - `core.lc_description_observation` 和 `lc_display_observation` 只能由 Listing 转化模块写入。
  - 抽屉和模型读的是经营事实；由经营事实模块写 Listing 转化模块的表会形成模块循环。
  - 展示观测是买家侧证据，卖家接口的数据不符合它的含义。
  - 它们的使用方是描述写入链，目前关闭。从这些事实补写 `lc_description_observation` 放到描述写入能力（W2）一起做。
- **AI 内容草稿**：
  - **投影** `LISTING_CONTENT_DRAFT` v1：
    - 商品卡：标题及长度、描述原文（合并空白，最多 3,000 字符）及长度、有无富内容、图片数、已填属性数；
    - 评分组：组评分、权重；未满分的组另带每个条件的键、Ozon 说明、是否满足、分值（满分的组不能再加分，只发分数，免得模型把可选条件当成提分手段）；卡上还没填的点名属性；
    - 内容评分、搜索人数、下单件数，以及"内容待提升""搜索曝光不足""有需求不成交"三条结论；
    - 前 5 个搜索词。

    不含任何价格、成本、利润。
  - **提示词** `listing-content-draft` v3（v1、v2 在试点商品上试过后收紧）：
    - 最多 1 个标题草稿（不超过 150 字符）、1 个描述草稿（700–1100 字符的纯文本）、2 个属性建议；
    - 只用数据里有的事实：数据里没有的材质、成分、尺寸、洗护、产地、保暖等一律不编，放进不确定项并写明去哪里找；
    - 搜索词只能在数据支持的范围内使用：买家搜"утепленный"（保暖），但商品卡没写保暖，就不能写进草稿；
    - 不扩展已有事实（例如"демисезонный"是春秋款，不能写成冬季）；预期效果不许承诺评分数据不支持的提升；风险必须针对草稿本身的措辞。
  - **试点上的表现**（同一个"其他属性"50 分的商品，三版各一次，每次 26–32 秒）：
    - v1 为了覆盖搜索词写了"утепленная"（保暖），并从"демисезонный"推出"Осень-Зима"（秋冬）系列；
    - v3 不再写保暖，材质、系列、洗护三个点名属性都列为不确定项并写明去哪里找，全部陈述通过校验；
    - 描述里仍可能轻微引申（写了"а также для начала зимы"，即也适合初冬），所以草稿必须人工核对。
  - **输出**：草稿是 `LISTING_CONTENT_REVIEW` 建议，`proposedParameters` 为 `contentField`（TITLE / DESCRIPTION / ATTRIBUTE）、`draftText`（俄语），属性建议另带 `attributeName`。
  - **校验**：
    - "数字只能照抄"也适用于草稿正文；
    - 属性名必须是投影里点名的属性，否则以 `DRAFT_ATTRIBUTE_NOT_NAMED` 拒绝；
    - 非内容类建议以 `CONTENT_DRAFT_ACTION_OUT_OF_SCOPE` 拒绝。
  - **缓存**：沿用内容摘要，商品卡、评分和搜索词都没变时直接返回上次的草稿。
- **接口**：
  - `GET /api/v1/console/stores/{storeId}/listing-variants/{variantId}/content`，需要 `DIAGNOSTIC_VIEW`：商品卡现状和评分组；
  - `POST /api/v1/console/explanations/listing-variants/{id}/content-drafts?storeId=…&window=D7`，需要 `DIAGNOSTIC_VIEW`：生成或沿用草稿；
  - `GET …/content-drafts/latest`，需要 `EVIDENCE_VIEW`：最近一次，不调用模型。
- **页面**：商品抽屉在"AI 解读"下方新增"内容优化"，依次显示：
  - 评分组：分数、权重、所在档位和下一档，点名要补的属性及是否已填；
  - 商品卡现状：描述、富内容、图片、已填属性；描述全文可展开、可复制；
  - AI 俄语草稿：每条草稿可一键复制，显示字符数、现有内容与草稿各覆盖了几个搜索词（按词干近似匹配），以及修改理由、预期效果、风险；不确定项写明去哪里找。
- **不做**：不写 Ozon（描述修改属于 W2，需要带商品写权限的 key 和逐项同意）；不读类目属性和属性字典。

## P7：促销算账

目标：回答"Ozon 的哪些活动值得参加、哪些商品参加会亏"。平台读取活动、候选商品和已参加商品，按活动价测算单件利润率，Qwen 解读取舍，人在卖家后台操作后回到平台记下决定。平台不参加、不退出任何活动，写入保持关闭。

- **接口**（官方 OpenAPI 2026-09-29 核验，都在 key 的 "Actions read-only" 角色内）：
  - `GET /v1/actions`：店铺可参加的活动，含类型、起止时间、冻结时间（之后只能降价、不能退出）、可参加和已参加的商品数、是否已参加、折扣；
  - `POST /v2/actions/candidates`、`POST /v2/actions/products`（v1 于 2026-10-13 停用）：每个活动的候选商品和已参加商品，含现价、活动价、最高活动价、推荐活动价、是否高于推荐价（可能被移出），以及加成区间和对应价格。两者每次只接受一个 `action_id`，按 `last_id` 翻页，每页最多 100 个商品。
- **按键请求（V0023）**：
  - 请求模板可以写 `{promotionKey}`：键是店铺最新一次活动快照里还没结束的活动，按文本顺序每次问一个，全部问完结束（`KEYS_EXHAUSTED`），键必须是纯数字；
  - 应答里不重复活动编号，所以原始观测记下这次请求问的键（`raw.raw_acquisition_observation.request_key`），标准化用新来源 `REQUEST_KEY` 把它写进事实；
  - 活动内翻页没有做：一页满 100 个商品时停下来交给人处理（`SCHEMA_DRIFT`），不静默截断；
  - 某个活动被答 404（读完列表后活动结束了）时保留这次应答，继续问下一个；其他拒绝（包括 400）照旧停下，免得系统性错误被逐个跳过；
  - 为此 `ops.acknowledge_checkpoint` 现在也接受完整的 404 应答推进检查点。V0009 的 `SHORT_PAGE_OR_NOT_FOUND`（最后一页之后答 404 即结束）原本就依赖这一点，此前会抛 MO009 回滚；其他拒绝仍不能推进检查点；
  - 没有当前活动时不调用 Ozon，运行直接成功（零页）；上次中断留下的位置超过今天的活动数时从头开始。
- **新表**：
  - `core.platform_promotion`：活动身份，属于商品与 Listing 模块，与商品身份放在一起；活动快照写它，采集从它读键；
  - `core.promotion_observation`：每次快照每个活动一行；
  - `core.promotion_item_observation`：每次应答每个商品每种身份（`CANDIDATE` 可参加、`PARTICIPANT` 已参加）一行。只有在最新活动快照之后读到的候选和已参加商品才算当前，这样空应答（商品都退出了）不会被旧数据顶替；
  - 什么算当前活动：最新快照不超过 48 小时（活动列表为空时不写行，更早的快照不能当作今天的活动），且活动还没结束。请求用的活动键和测算、页面、AI 用同一规则；
  - 加成和折扣是 Ozon 给的 double，按 4 位小数取整记录，不套用金额的精度规则（否则浮点尾数会让整条记录被拒）；
  - `ops.promotion_decision`：人工决定记录，只追加。
- **测算**（`PromotionEconomicsQuery`，与 P3 同一口径）：最新价格条款、履约方式、Ozon 佣金和物流、按商品税率扣增值税、映射的单件成本，复用 `ListingUnitEconomics`。每个商品给出：
  - 现价、活动价（已参加的商品）、最高活动价、推荐活动价、最大加成价下的预估利润率，以及保本价；
  - 判断：已参加的商品按它在活动里的价格（活动价），可参加的商品按最高活动价（参加后不可能比这个价更赚钱）。`JOIN_KEEPS_FLOOR` 保住利润率下限、`JOIN_BELOW_FLOOR` 盈利但低于下限、`JOIN_LOSES` 亏损、`UNKNOWN` 缺输入（缺什么写在 `missing` 里）。试点数据（2026-10-01）：已参加的 28 个商品里，21 个的活动价等于当前买家价，而最高活动价高于当前价，所以已参加的商品不能按最高活动价判断。
  - 假设：物流沿用 Ozon 按现价给出的金额，活动价更低时部分物流可能更低，所以按活动价的估算偏保守（实际利润率可能略高）。
- **出站白名单**：三条精确路径规则（`exact-path: true`，出站规则新增的选项）。这些路径下面挂着写方法（`/v1/actions/products/activate`、`/v2/actions/products/deactivate` 等），按前缀放行会把写方法一起放出去。
- **定时采集**：三个数据集都按每日快照排在内容评分之后，活动列表先于候选和已参加商品。
- **诊断规则 `PROMOTION_OPPORTUNITY`**（规则 v1，严重度 INFO）：
  - 新指标 `PROMOTION_BEST_MARGIN`：商品作为候选能参加的当前活动里，按最高活动价估算的最佳单件利润率，口径同 `PROJECTED_UNIT_MARGIN`；不能参加任何活动或缺输入时为不可用；
  - 最佳利润率 ≥ 利润率下限时触发，店铺诊断里显示为"可参加活动"，提示去"促销活动"页查看并在卖家后台人工参加；
  - 商品解读和店铺周诊断的提示词都加了这条规则的含义和 `PROMOTION_REVIEW` 建议类型（商品提示词 v7、店铺提示词 v5、Listing 辅助提示词 v6），规则细节 `promotionMargin`、`minimumUnitMarginRate` 可以出境。
- **AI 促销取舍建议**：
  - **投影** `PROMOTION_REVIEW` v1：最多 3 个活动（已参加的优先，其次按商品数），每个活动的条款、四种判断各有几个商品（数量完整）；每个活动最多 5 个商品（先放亏损或低于下限的已参加商品，再按搜索人数），每个商品的现价、活动价（已参加）、最高活动价、推荐活动价及各自的预估利润率、判断、判断所用价格（已参加按活动价、可参加按最高活动价）比最低竞品价高或低多少，以及近 7 天搜索人数和下单件数。只发比率和平台自己的价格，不发成本、利润、保本价金额。
  - **提示词** `promotion-review` v1：一句结论、最多 3 条建议（每条针对一个活动，说明哪些商品参加或保留、哪些不参加或退出、可接受的最低活动价）、最多 3 条依据（只能引用商品的搜索人数和下单件数）、最多 2 个不确定项。亏损商品不许建议参加或保留；低于下限的商品只能在有搜索需求时作为"用利润换销量"的明确取舍；要退出的必须在冻结日前退出。
  - **校验**：数字只能照抄；建议只能是 `PROMOTION_REVIEW`（或缺成本时的 `COST_DATA_REVIEW`），其他以 `PROMOTION_REVIEW_ACTION_OUT_OF_SCOPE` 拒绝。
  - **缓存**：活动、测算和需求都没变时直接返回上次的建议。
- **人工决定**：新动作权限 `PROMOTION_DECISION_RECORD`（不需要第二重验证，OWNER 和 OPS_LEAD 角色默认带）。现有 Owner 要补一条授权：

  ```bash
  make owner-grant ACTION=PROMOTION_DECISION_RECORD API=http://127.0.0.1:9999
  ```

  决定是 `JOINED`（已在后台参加或保留，可记设置的活动价）、`SKIPPED`（不参加）、`LEFT`（已在后台退出），可附原因，写入审计。
- **控制台接口**：
  - `GET /api/v1/console/diagnosis/stores/{storeId}/promotions`，需要 `DIAGNOSTIC_VIEW`：当前活动和每个商品的测算；
  - `GET /api/v1/console/stores/{storeId}/promotion-decisions`，需要 `DIAGNOSTIC_VIEW`：每个活动每个商品最新的决定；
  - `POST /api/v1/console/stores/{storeId}/promotions/{promotionId}/decisions`，需要 `PROMOTION_DECISION_RECORD`：记一条决定；
  - `POST /api/v1/console/explanations/stores/{storeId}/promotions?window=D7`，需要 `DIAGNOSTIC_VIEW`：生成或沿用 AI 建议；`GET …/promotions/latest`，需要 `EVIDENCE_VIEW`：最近一次，不调用模型。
- **页面**：侧边栏"店铺诊断"下方新增"促销活动"：AI 促销取舍建议；每个活动一张卡片（是否已参加、时间、冻结日、折扣、可参加/已参加数、按最高活动价的判断汇总），卡片里是商品表（身份、现价/最高活动价/推荐活动价及利润率、加成、判断和保本价、人工决定）。
- **试点 key（2026-10-01）**：旧 key 被 Ozon 停用后换了新 key。Owner 决定新 key 保留全部 41 个角色（含可改数据的角色），由 Owner 亲自用 `--allow-write-roles` 探测（证据有效期到 2026-10-30，key 到期 2026-12-29）。平台本身仍只读：本地 Ozon 出站白名单只有读取路径，写入能力关闭。每次能力探测都会核对 key 角色，所以促销能力的探测也要带这个开关。
- **接入步骤**：

  ```bash
  python3 scripts/ozon_pilot.py probe --pilot pilot --capability actions --official-source-file ~/Downloads/swagger.json --allow-write-roles
  python3 scripts/ozon_pilot.py probe --pilot pilot --capability action-candidates --official-source-file ~/Downloads/swagger.json --allow-write-roles
  python3 scripts/ozon_pilot.py probe --pilot pilot --capability action-products --official-source-file ~/Downloads/swagger.json --allow-write-roles
  ```

  按真实应答核对映射后去掉 `probe_only`，再对三个能力各执行 `make ozon-setup … OPERATOR=claude-for-owner`，由 Owner 执行 `make ozon-verify`，然后 `make ozon-run` 加 `make ozon-normalize` 各跑一次（先 `actions`）。
- **不做**：不自动参加或退出活动（`/v1/actions/products/activate`、`deactivate` 属于写入，需要带写权限的 key 和逐项同意）；不做活动内翻页（试点店铺 41 个商品，一页足够）。

## 调校：按真实数据复核已完成的能力

接入真实的 Ozon 和 Qwen 之后，回头复核 P1–P7 里在合成数据上设计的规则、提示词和采集行为。阈值没有改动（Owner 尚未决定），真实分布写在 PR 里。

- **`DATA_BLOCKED` 在零销量时不再阻断（V0024）**：
  - 问题：完整度是"已实现利润输入"的占比，其中销售额、佣金、退货、广告、税费都要有成交才会有。试点店铺没有订单，占比只有单件成本这一项（12.5%），于是 41 个商品全部判为 CRITICAL，排在它后面的 8 条规则都记成"被前置规则阻断"——包括 11 个零库存商品的断货风险。商品解读把"数据完整度 12.5%、严重级别阻断"当成第一条事实。
  - 现在：窗口内既没有完成件数、也没有下单件数（为 0 或没有记录）时，规则以 `INSUFFICIENT_SAMPLE` 拒答（细节 `condition=NOTHING_SOLD`），后面的规则按各自的输入回答。映射未解决、输入过期或冲突、有成交但缺利润输入，仍然阻断。规则版本仍是 1，V0024 更新了规则说明并登记 `COMPLETED_UNITS`、`ORDERED_UNITS` 两个可选输入。
  - 试点结果（2026-10-01 重算）：`DATA_BLOCKED` 41 个拒答；`STOCKOUT_RISK` 11 个触发（`NO_PLATFORM_STOCK`）、30 个通过（没有销量，算不出可售天数）；负利润、退货、曝光、点击、转化、广告、低于最低价各自以"缺少所需指标"拒答，并写明缺的是哪个指标。
  - 写入不受影响：真实写入由 `marketops.production-writes.enabled: false` 全局关闭。P8 做调价时要重新设计零销量商品的写入护栏（按预估单件经济，而不是已实现利润）。
- **不重复计数零库存**：店铺诊断页和店铺 AI 投影本来就按库存值统计"无库存"（`WITHOUT_STOCK`）。`STOCKOUT_RISK` 的 `NO_PLATFORM_STOCK` 结论和它重复，所以页面标签、店铺结论和店铺投影都不再单独显示它，也不参与"严重商品优先"的排序；将来有销量后"可售天数不足"的结论照常显示。
- **AI 提示词与投影**：
  - 商品解读提示词 v9（Listing 辅助 v8 沿用同一段）：讲清规则结论的 `TRIGGERED`、`CLEAR`、`DECLINED` 和拒答原因，拒答的规则只能作为不确定项提；说明 `DATA_BLOCKED` 以样本不足拒答表示窗口内没有成交，以及 `STOCKOUT_RISK` 的含义。
  - 商品投影不再发送 `DATA_COMPLETENESS`（零销量时它永远很低，模型会照抄占比而不是讲商品）；`STOCKOUT_RISK` 的 `platformAvailableUnits`、`stockCoverDays`、`stockCoverDaysFloor` 可以出境。
  - 自商品解读 v8、店铺周诊断 v6、内容草稿 v4 起：每个成员都必须是 JSON 数组（单条也一样）；比率只描述它名字里的两个价格，不能挪到别的价格上。
  - 两条诊断日志：`ai_claim_unknown_members`（只记多出来的成员名）、`ai_claim_reference_unresolved`（只记计数：引用放错了列表、引用了没给过的编号，或列表格式不对）。
  - 实测（试点，2026-10-01）：同一个零库存商品，v8 的 12 条陈述里 3 条（都是库存为 0）因引用无法解析被拒，首条事实是"数据完整度 12.5%"；v9 的 12 条全部通过，首要建议是补货并引用断货风险结论。内容草稿 v4 7 条全部通过；店铺周诊断 v6 完整（1 条把店铺合计写成事实的常规拒绝）。
- **采集**：
  - 失败重试的等待时间每次翻倍（默认重试 3 次，等 2、4、8 分钟），最长 30 分钟，减少 Ozon 按分钟限流（429）时的连续失败；
  - 店铺诊断页顶部的采集健康提示：key 被 Ozon 拒绝（受阻运行的最后应答是 HTTP 401/403）、其他受阻运行、证据已过期或 7 天内到期、读取凭据缺失或 14 天内到期时提示，并链接到"数据采集"页；一切正常时不显示；
  - "数据采集"页的受阻运行显示最后一次应答状态（例如 HTTP 403）；
  - `GET /api/v1/console/stores/{storeId}/data-collection` 新增 `credentialExpiresAt` 和 `jobs[].liveRunLastAnswer`。
- **补数的探测入口（待 Owner 运行）**：`scripts/ozon_pilot.py` 新增四个只探测能力（`probe_only`，暂无映射）：
  - `seller-rating`：`POST /v1/rating/summary`，卖家评级各组指标；
  - `discount-requests`：`POST /v2/actions/discounts-task/list`，买家申请的折扣（映射时不取经办人姓名和邮箱）；
  - `warehouses`：`POST /v2/warehouse/list`，FBS 仓库（映射时不取地址和电话）；
  - `delivery-methods`：`POST /v2/delivery-method/list`，发货方式。

  ```bash
  python3 scripts/ozon_pilot.py probe --pilot pilot --capability seller-rating --official-source-file ~/Downloads/swagger.json --allow-write-roles
  python3 scripts/ozon_pilot.py probe --pilot pilot --capability discount-requests --official-source-file ~/Downloads/swagger.json --allow-write-roles
  python3 scripts/ozon_pilot.py probe --pilot pilot --capability warehouses --official-source-file ~/Downloads/swagger.json --allow-write-roles
  python3 scripts/ozon_pilot.py probe --pilot pilot --capability delivery-methods --official-source-file ~/Downloads/swagger.json --allow-write-roles
  ```

  按真实应答确定数据集、事实表和映射后，在后续 PR 里接入（新迁移、定时采集、店铺诊断的评级与发货信息、按商品的折扣申请）。探测结果和接入情况见下一节。

## 店铺状态：卖家评级与仓库（V0025）

四条补数探测（Owner 2026-10-01 运行）的结论：

| 能力 | 真实应答 | 处理 |
| --- | --- | --- |
| `seller-rating` | 无 Premium / Premium Plus，罚分未超限，本地化指数计算于 2026-09-23；4 组 10 项评级全部为 0（6 项 `OK`、4 项 `UNKNOWN_STATUS`），因为还没有订单 | 接入（本节） |
| `warehouses` | 1 个 FBS 仓库，状态 `created`（启用），自送到投放点，每周 7 天，无订单上限 | 接入（本节） |
| `delivery-methods` | 0 条。官方说明 `/v2/delivery-method/list` 只列 rFBS 仓库的发货方式，列 FBS 的 v1 已于 2026-04-07 停用；FBS 由 Ozon 从投放点配送 | 不接入，探测入口保留给 rFBS |
| `discount-requests` | 按最后一条的 id 翻页后共 331 条（2026-04-16 至 09-27，批准 329、拒绝 2），6 个整页加 1 个 31 条的短页，无重复。买家要的折扣中位数 5%；按月 4 月 22、5 月 97、6 月 161、7 月 31、8 月 16、9 月 4。涉及 64 个 SKU，只有 8 个在当前商品目录里（共 11 条申请），已批准的优惠截至 2026-10-01 全部过期 | 探测按短页结束（50 条一页，短页后再问一次确认为空），待重跑记录证据；接入需要引擎支持"按最后一条记录翻页"，是否现在做待 Owner 决定 |

另外在官方文档里找到两个与"为什么卖不动"直接相关的只读接口，已加探测入口。Owner 2026-10-01 运行：`warehouse-restrictions` 三次都答 `{"warehouse_ids":[]}`，没有仓库存在 Ozon 无法配送的商品，所以 `restricted-products` 无需查询，配送限制可以排除：

- `warehouse-restrictions`：`POST /v1/warehouse/warehouses-with-invalid-products`，哪些仓库里有 Ozon 无法从该仓库配送的商品（不接受请求体）；
- `restricted-products`：`POST /v1/warehouse/invalid-products/get`，按仓库列出无法配送的商品和超限项（长、宽、高、重量、尺寸和、体积重、体积、价格、最长边，以及低于下限还是高于上限），按 `last_id` 翻页。探测只问上一条探测点名的那一个仓库；没有被点名的仓库时直接提示无需查询。

```bash
python3 scripts/ozon_pilot.py probe --pilot pilot --capability warehouse-restrictions --official-source-file ~/Downloads/swagger.json --allow-write-roles
python3 scripts/ozon_pilot.py probe --pilot pilot --capability restricted-products --official-source-file ~/Downloads/swagger.json --allow-write-roles
python3 scripts/ozon_pilot.py probe --pilot pilot --capability discount-requests --official-source-file ~/Downloads/swagger.json --allow-write-roles
```

**接入内容（V0025）**：

- **数据集**：
  - `SELLER_RATING`：评级概况，一次应答一条记录，读应答根上的 `premium`、`premium_plus`、`penalty_score_exceeded` 和 `localization_index`（主映射的记录指针为空，即整份应答）；
  - `SELLER_RATING_ITEM`（伴随映射）：每项评级一条，读 `groups[].items[]` 的键、名称、值类型、方向、Ozon 判定（`OK` / `WARNING` / `CRITICAL` / `UNKNOWN_STATUS`）、当前值、上期值和变化，以及所在组名；
  - `FBS_WAREHOUSE`：每个仓库一条，读状态、类型、rFBS、快递、大件、自动组装、首公里、工作日数、交接和最短组装时间（分钟）、订单上限（`-1` 为无上限）、暂停时间、创建和更新时间、时区。**不读**仓库名称、地址、坐标、电话、快递员备注、投放点和时段。
- **新表**（只追加，按店铺归属）：`core.seller_rating_summary_observation`、`core.seller_rating_item_observation`、`core.warehouse_observation`。店铺最新一次观测就是当前状态，Ozon 不再列出的评级或仓库不会被当成当前的。
- **评级数值**：`RATIO` 类评级是 0 到 1 的比例（试点"价格指数红色区商品占比"为 1，而有价格指数的 14 个商品全是 RED），页面按百分比显示；`PERCENT` 已是百分比；其余按原样显示。
- **漂移记录**：仓库里有意不读的字段（名称、地址、电话等）会以字段路径记为 `staging.schema_drift_observation`（只有路径，没有取值），和其他数据集不读的字段一样。主映射读整份应答、伴随映射读其中的列表时，伴随映射读的列表不再算主映射的漂移（`NormalizationRunner.coveredBy`）。
- **仓库状态含义**（官方 `/v1/warehouse/list` 的对照表）：`created` 启用、`new` 启用中、`disabled` 已归档、`blocked` 已封禁、`disabled_due_to_limit` 暂停（达到订单上限）、`error` 出错。
- **定时采集**：两个数据集都按每日快照，排在促销之后。
- **出站白名单**：`/v1/rating/summary`、`/v2/warehouse/list` 两条精确路径规则（`/v2/warehouse/list` 旁边有 `/v1/warehouse/archive` 等写方法）。
- **控制台接口**：`GET /api/v1/console/stores/{storeId}/standing`，需要 `DIAGNOSTIC_VIEW`，读取记审计。返回评级概况、最新一次评级和仓库，各自带数据时间；尚未采集的部分为空。
- **页面**：
  - 店铺诊断页底部新增"店铺状态"：账户（Premium、Premium Plus、罚分、本地化指数）、卖家评级表（中文名、Ozon 判定、当前值和上期值；没有判定的评级显示"暂无数据"，不显示成 0；数值按 Ozon 原样显示）、仓库（状态、类型、首公里、工作日、订单上限、暂停）；
  - 页面顶部在罚分超限、仓库不是"启用"、评级被判为严重或需注意时提示；一切正常时不显示；
  - "诊断结论"下方的说明按采集到的订阅状态写"本店未订阅"或"本店已订阅，但这些数据还没有接入"，没采集到时不写。
- **接入步骤**：

  ```bash
  make ozon-setup CAPABILITY=seller-rating API=http://127.0.0.1:9999 OPERATOR=claude-for-owner
  make ozon-setup CAPABILITY=warehouses API=http://127.0.0.1:9999 OPERATOR=claude-for-owner
  make ozon-verify CAPABILITY=seller-rating API=http://127.0.0.1:9999
  make ozon-verify CAPABILITY=warehouses API=http://127.0.0.1:9999
  ```

  然后两个能力各跑一次 `make ozon-run` 和 `make ozon-normalize`，之后由定时采集每天更新。

## P8：定价建议闭环（第一部分：建议与审阅）

目标：确定性规则给出调价建议，进入现有的“审核调价建议”。平台不改价，真实写入仍由 `marketops.production-writes.enabled: false` 全局关闭。Owner 2026-10-01 的两项决定：

- 加一条“有需求不成交且利润有空间”的降价规则（`PRICE_HEADROOM`）。按原计划只有 `PRICE_GAP_REDUCIBLE` / `PRICE_GAP_PARTIAL` 会产生改价建议，而试点 14 个有竞品价的商品全是结构性价差，这两条规则一条建议也给不出来。
- P8 先做建议与审阅。护栏在试点上缺这些前提：商业策略、经济性 profile、新鲜度水位、零订单下的完整度口径、履约方式声明。这些放到开启写入（W1）前的单独阶段做。认可某条建议的话，在 Ozon 卖家后台手工调价，再回平台记录。

- **规则 `PRICE_HEADROOM`**（V0026，规则 v1，严重度 INFO，不阻断写入）：
  - 触发条件：近 7 天搜索人数 ≥ 需求下限（1,000）、有库存、下单 0、预估利润率高于下限（15%），而且买家价降到目标利润价的幅度 ≥ 最小降价空间（`price-headroom-minimum-rate: 0.03`，Owner 2026-10-01 按建议值决定）；
  - 买家价高于可比的 Ozon 竞品最低价时交给价格差规则处理，本规则判为通过（`condition=PRICE_GAP_RULES_APPLY`）；
  - 细节记录可降空间 `priceRoom`、预估利润率、利润率下限、搜索人数、目标利润价。其中比例可以出境给 Qwen，目标利润价不出境。
- **建议生成**（`PriceSuggestionService`）：
  - 依据：店铺最新一次 7 天计算。依次看 `PRICE_GAP_REDUCIBLE`、`PRICE_GAP_PARTIAL`、`PRICE_HEADROOM`；有结构性价差、无库存或不可售的商品不给建议。
  - 价格只来自确定性计算：
    - 不低于目标利润价（向上取整到整卢布，保证守住下限）；
    - 单次最多降 `max-step-rate`（10%，向上取整，保证降幅不超过上限；Owner 2026-10-01 按建议值决定，冷却期 `validation-horizon-days` 14 天同）；
    - 可降价追平竞品时，不低于竞品最低价。竞品价是 C 级平台分析数据，只用来限定一条候选，由人审阅，不会授权自动改价（HR-07）。
  - 价格都是买家价（含卖家促销）。
  - 利润率估算：用与诊断同一时点的费率和成本，经新的 `ListingPriceEstimateQuery` 计算，与 P3/P7 同一口径（共用 `ListingEconomicsInputs`）。
  - 建议内容：`targetPrice`；预期效果里记依据、现价、目标价、降幅、可调区间（下限 = 守住利润率下限的最低价，上限 = 现价）、现在和目标价下的利润率、竞品价、搜索人数。风险按降幅：5% 以内为低，10% 以内为中。验证期 14 天。依据（规则结论和指标值）都挂在建议上。
  - 流程：提出（DRAFT）→ 记录一次护栏预览 → VALIDATED → READY_FOR_REVIEW。VALIDATED 的含义是“护栏已为预览评估过”，不要求通过；批准时护栏会重新评估。
  - 何时生成：
    - 定时采集后的 7 天自动重算之后，新增、更新、撤下的条数分别记进 RECALCULATED 事件的 `priceSuggestions`、`priceSuggestionsRefreshed`、`priceSuggestionsWithdrawn`（试点 2026-10-01 01:00 UTC 的定时重算已自动跑过一次）；
    - 页面上点“重新计算诊断”之后；
    - 也可以调用 `POST /api/v1/console/workflow/stores/{storeId}/price-suggestions`，需要 `RECOMMENDATION_MANAGE`。
  - 保持一致：
    - 每次先让过期的建议到期；
    - 本服务提出、还没人决定的建议，若最新计算给出的价格或依据数字有变化，就作废重提（`SUPERSEDED_BY_NEWER_CALCULATION`）；若不再需要，就撤下（`NO_LONGER_SUGGESTED`）；
    - 某个商品记录过决定后，验证期（14 天）内不再给新建议。
- **手工决定**（`PriceDecisionService`，表 `ops.price_decision`，只追加，一条建议一条）：
  - `POST /api/v1/console/workflow/recommendations/{id}/price-decision`，需要 `RECOMMENDATION_MANAGE`（已有授权，不需要第二重验证）：
    - `APPLIED_IN_SELLER_OFFICE`：记下实际设置的买家价，建议转为 CANCELLED（原因 `APPLIED_IN_SELLER_OFFICE`）；
    - `NOT_APPLIED`：可附原因，建议转为 REJECTED（还在草稿时转为 CANCELLED）。
  - 两种决定都不是“批准”：批准是授权平台写入，必须护栏通过；这里平台什么也没写。
  - `GET …/stores/{storeId}/price-decisions?subjectId=`，需要 `DIAGNOSTIC_VIEW`。
- **页面**：
  - 店铺诊断的结论里新增“有降价空间”；“重新计算诊断”之后会同时生成建议，并提示新增、更新、撤下各几条；
  - 商品详情新增“调价建议”：依据、买家价 现价 → 目标价（降幅）、可调区间、预估利润率变化、竞品价、搜索人数、护栏结论（未通过时列出原因并说明怎么做），以及“已在 Ozon 后台改价”“不采纳”“打开审阅”三个按钮；
  - “审核调价建议”的审阅抽屉也显示依据、可调区间和利润率变化，币种取建议自己的币种。
- **AI**：
  - 商品解读提示词 v11（Listing 辅助 v10）、店铺周诊断 v7，讲清 `PRICE_HEADROOM`。模型若建议改价，只引用可降空间这个比例，不自己算价格、不填价格参数（平台已经给出建议价）；
  - 投影的规则细节加入 `PRICE_HEADROOM` 的搜索人数、预估利润率、利润率下限和可降空间。
- **试点结果（2026-10-01）**：

  | 商品 | 数量 | 降幅 | 预估利润率变化 |
  | --- | --- | --- | --- |
  | 659 系列 | 5 个 | 单次 10% | 34.81% → 33.87%（NIBAI-M 25.59% → 23.63%） |
  | W-2618-KASE | 3 个 | 8.1%–10% | 降到 15%–16.09% |
  | W-657 | 4 个 | 5.47%（降到下限） | 降到 15% |
  | W-2643-D54-4XL | 1 个 | 6.56% | 降到 15% |

  - 13 条建议都是 `PRICE_HEADROOM`，全部进入待审阅；
  - 护栏预览全部为 BLOCK：没有生效的定价策略、所需指标缺失（单件目标利润、安全缓冲）、缺少经济性 profile；
  - `PRICE_GAP_REDUCIBLE` / `PARTIAL` 为 0。

## P8：定价建议闭环（第二部分：买家折扣申请）

买家可以在 Ozon 上申请以更低的价格买某个商品，这是买家直接说出的“愿意付的价格”。Owner 2026-10-01 决定把它放在 P8 接入。

- **接口**：`POST /v2/actions/discounts-task/list`（官方 OpenAPI 2026-10-01 核对）。列出所有状态（`NEW` 待处理、`APPROVED` 已批准、`DECLINED` 已拒绝）的申请，每页最多 50 条。批准和拒绝是旁边的写方法（`/v1/actions/discounts-task/approve`、`/decline`），平台不调用。
- **试点探测（2026-10-01）**：
  - 共 331 条申请（2026-04-16 至 09-27），批准 329、拒绝 2、待处理 0；6 个整页加 1 个 31 条的页，id 降序、无重复，再往后一页为空；
  - 涉及 64 个 SKU，只有 8 个在当前商品目录里（11 条申请）；
  - 两条被拒绝的申请都是在创建 48 小时后、到期时被拒，处理人为空；
  - 申请里的“原价”是买家申请时看到的买家价（含当时的促销），不能直接和现价比：目录内商品的申请原价只有现在卖家价的 15%–50%。2026-10-01 00:10 UTC 采集的价格里，14 个商品的买家价平均上涨 54%，其中 8 个不再有促销价；时间上与促销“Акция для товаров со схемой FBS”（15 个商品参加）在 2026-09-30 21:00 UTC 结束吻合。
- **采集引擎：新的分页模型 `LAST_RECORD_KEY`**：
  - 应答里没有位置字段，下一页要带上一页最后一条记录的 id（`last_id`；官方说第一次请求留空）；
  - 端点记录 `continuation_pointer` 为记录内的 id 指针（`/id`），`records_pointer` 为 `/tasks`，结束规则只能是 `EMPTY_RECORDS`，即拿到空页才结束；
  - 请求模板用新占位符 `{lastRecordKey}`：第一页渲染为 `null`（proto3 JSON 把它当作未填），之后渲染为上一页最后一条的 id。只接受正整数，因为它以裸 JSON 数字放进请求体；下一页的 id 与刚问过的相同时按 schema drift 停下，避免反复请求同一页；
  - 页大小写在模板里（`"limit":50`）：这个接口只允许 5–50，而 `{limit}` 渲染为 100；以空页结束，所以不依赖页大小。每次运行最多 20 次调用，即一次最多读 1000 条申请；
  - V0027 扩展了分页模型和检查点的取值、占位符白名单，并约束 `{lastRecordKey}` 只能用在这个模型下。V0013 的证据审批已经要求翻页端点有 `continuation_pointer`，这个模型满足。
- **数据集 `DISCOUNT_REQUEST`**（V0027）：
  - 每条申请一条记录，按店铺归属，表 `core.discount_request_observation`（只追加），每次采集都写一次。因为多数申请的 SKU 已不在目录里，读取时再按 SKU 关联到商品（目录里恰好一个变体有这个 SKU 时）；
  - 读：申请 id、SKU、商品名、状态、申请时间、处理时间、到期时间（新申请的处理期限）、原价、买家要价、要求的折扣（%）、要求的数量、批准价、批准的数量、是否自动处理；
  - 应答不带币种：金额按店铺币种记录（试点店铺和 41 个商品标价都是 RUB）；店铺没有币种时不记金额；
  - **不读**：处理申请的卖家员工（邮箱、姓、名、父称）；`approved_discount`（文档说单位是卢布，试点 329 条批准申请里它都是相对原价的百分比，与批准价重复）；`edited_till`（试点上都等于处理时间）；`min_auto_price`、`reduction_factor` 和自动处理设置（诊断用不到）。不读的字段只以字段路径记入漂移，不存取值。
- **定时采集**：每日快照，排在仓库之后。出站白名单为 `/v2/actions/discounts-task/list` 精确路径。
- **控制台接口**：`GET /api/v1/console/stores/{storeId}/discount-requests?subjectId=&limit=`，需要 `DIAGNOSTIC_VIEW`，读取记审计。返回：
  - 数据时间；
  - 计数：总数、批准、拒绝、待处理、SKU 数（其中在目录里的）、目录内商品的申请数、要求折扣中位数、最早和最近一次申请、待处理申请最早的处理期限；
  - 按月（UTC）申请数；
  - 店铺范围时，返回申请涉及的 SKU（当前目录里的在前，其余按申请数，最多 10 个）；
  - 最新的申请（默认 20 条）。

  带 `subjectId` 时只看这个商品。
- **页面**：
  - 店铺诊断新增“买家折扣申请”：计数、按月分布、申请涉及的商品（当前目录里的在前，可以点开商品详情）。有待处理的申请时提示到 Ozon 卖家后台处理，并给出最早的期限；
  - 商品详情在“调价建议”下面显示这个商品的申请：一句汇总，加申请明细表（申请时间、状态、原价、买家要价和折扣、批准价、处理方式或处理期限）。
- **探测与接入步骤**：

  ```bash
  python3 scripts/ozon_pilot.py probe --pilot pilot --capability discount-requests --official-source-file ~/Downloads/swagger.json --allow-write-roles
  make ozon-setup CAPABILITY=discount-requests API=http://127.0.0.1:9999 OPERATOR=claude-for-owner
  make ozon-verify CAPABILITY=discount-requests API=http://127.0.0.1:9999
  make ozon-run CAPABILITY=discount-requests API=http://127.0.0.1:9999
  make ozon-normalize CAPABILITY=discount-requests API=http://127.0.0.1:9999
  ```

  探测按后端的请求方式翻页（第一页 `last_id` 为 `null`，到空页结束）。`ozon-verify` 现在会检查探测证据的结束方式是否符合当前定义的结束规则；按旧定义（短页结束）记录的证据会被拒绝，并提示重新探测。
- **试点结果（2026-10-01）**：
  - 探测：8 页、331 条，以空页结束；第一页 `last_id: null` 被 Ozon 接受，条数与按旧方式探测时一致。两位 Owner 核验通过，证据有效至 2026-10-31；
  - 核验通过后，定时采集立刻自动跑了一次：8 页（7 次 NEXT、1 次 END），写入 331 条事实，没有拒收；
  - 紧接着的手动运行与它同在一分钟内，第 3 次调用被每分钟 10 次的上限拒绝（`RETRY_WAIT`），随后由定时采集从检查点（第 2 页最后一条的 id）接着翻完，也读到 331 条；
  - 漂移只记了刻意不读的字段路径（处理人、`approved_discount`、`edited_till` 等），没有取值；
  - 页面：
    - 店铺层：331 条（批准 329、拒绝 2、待处理 0），涉及 64 个 SKU，其中 8 个在当前目录（11 条），要求折扣中位数 4.97%；按月为 4 月 22、5 月 97、6 月 161、7 月 31、8 月 16、9 月 4 条；
    - 目录内 8 个 SKU 各 1–3 条，买家要求的折扣在 3%–5%（单条最高 13.19%）；
    - 商品详情的明细（原价、要价和折扣、批准价、人工或自动处理及日期）与 Ozon 记录一致。

## 买家价大幅上涨（V0028）

Owner 2026-10-01 决定加入。起因是促销“Акция для товаров со схемой FBS”（本店 15 个商品参加）在 2026-09-30 21:00 UTC 结束：下一次价格采集时，8 个商品的买家价没有了促销价，比之前高了 82%–98%。诊断里没有任何结论提到这件事，09:00 的定时重算还按翻倍后的价格重新给出了调价建议。

- **指标 `RECENT_LOW_BUYER_PRICE`**（近期最低买家价，MONEY）：
  - 窗口结束前回看期（`buyer-price-jump-lookback-days`，建议 7 天）内各次价格观测里最低的买家价（有卖家促销价时取促销价，否则取售价），只看与最新观测相同币种的价格；
  - 指标同时引用最低价那次和最新那次观测；新鲜度按最新那次计。最低价可能是几天前的，比较的本来就是它，不能算数据过期。
- **规则 `BUYER_PRICE_JUMP`**（买家价大幅上涨，WARNING，不阻断写入，不受实际利润数据阻断）：
  - 最新买家价比近期最低买家价高出不少于 `buyer-price-jump-minimum-rate`（建议 20%）时触发；
  - 细节记录买家价、近期最低买家价、上涨比例 `riseOverRecentLow`、阈值和回看天数；
  - 新价格持续满一个回看期后，最低价跟上来，结论自动消失。
- **近期结束的促销**：`GET /api/v1/console/diagnosis/stores/{storeId}/promotions/ended?days=7`（`DIAGNOSTIC_VIEW`），返回近 N 天（1–31）结束的促销，按各自最新一次快照给出名称、起止时间、本店是否参加和参加商品数。
- **页面**：
  - 店铺诊断顶部提示：近 N 天有几个商品的买家价大幅上涨、比近期最低价高多少、同期结束且本店参加过的促销（没有则说明可能是改了价），附“查看商品”（按这条结论筛选）和“打开促销活动”；
  - 诊断结论新增“买家价大幅上涨”，排在“买家不可购买”之后；商品详情的结论里显示买家价、近期最低买家价和上涨比例。
- **AI**：商品解读提示词 v12（Listing 辅助 v11）、店铺周诊断 v8，讲清这条规则。投影细节可以发出两个价格（都是 Ozon 上给买家的价格）和上涨比例；比例按它比较的对象命名为 `riseOverRecentLow`，避免被挂到别的价格上。
- **阈值（Owner 2026-10-01 按建议值决定，#71）**：最小涨幅 20%、回看 7 天。试点上触发的 8 个商品涨幅都在 82% 以上，其余 6 个有变动的商品涨幅都不到 10%，阈值取 10%–80% 结果相同。
- **调价建议暂停**（Owner 2026-10-01 决定）：有这条结论的商品不给调价建议，还没人决定的建议撤下（原因 `PAUSED_BY_BUYER_PRICE_JUMP`）。
  - 原因：建议依据的搜索和“没人下单”大多发生在原来更低的价格下，按新价格再降一步没有依据；
  - 价格稳定满一个回看期、结论消失后，按新价格重新计算；
  - 每次生成的结果和定时重算事件里记录 `paused`（本来会给建议、因跳涨暂停的商品数）；
  - 商品详情的“调价建议”说明暂停原因，重算后的提示也会写明暂停几个；
  - 试点（2026-10-01）：重算后 6 条在审建议以 `PAUSED_BY_BUYER_PRICE_JUMP` 撤下（W-657 的 4 个、W-2618-KASE-S/L），全部属于跳涨商品；不是跳涨商品的 7 条照常在审。
- **试点结果（2026-10-01，窗口截至 02:00 UTC 的重算）**：
  - 41 个商品都有近期最低买家价，置信度为 `CANONICAL_CONFIRMED`，没有被判为过期；
  - 规则触发 8 个（WARNING）、未触发 33 个、拒答 0 个：W-657 的 6 个商品涨幅 97.87%，W-2618-KASE-S/L 涨幅 82.01%；
  - 店铺诊断顶部提示点名“Акция для товаров со схемой FBS”（09-30 结束，15 个商品参加）；同一天结束的“Распродажа летнего”本店没参加，不列出；
  - 结论卡排在“无库存”之后，“查看商品”按这条结论筛选出 8 个商品。

## 写入前的护栏前提（第一部分：数据前提）

目标：让调价建议能在平台内通过护栏、完成审批。真正改价（W1）不在这个阶段。

### 梳理结果（2026-10-01）

试点上所有建议的护栏预览都被三项拦下：没有生效的商业策略（`NO_POLICY_IN_FORCE`）、缺经济性 profile（`ECONOMICS_PROFILE_MISSING`）、缺单件目标利润和安全缓冲两个指标（`REQUIRED_METRIC_UNAVAILABLE`）。这三项补齐后，还有四处会继续拦截：

| 卡点 | 原因 | 处理 |
| --- | --- | --- |
| 数据新鲜度 | 护栏要求 8 个数据源（价格、库存、销售、退货、财务费用、广告、内部成本、商业输入）都有已核验的 `core.source_feed_watermark`，且不超过策略的 `MAX_INPUT_AGE_SECONDS`。代码里没有任何地方写入它 | 本 PR |
| 数据完整度 | 按 8 项利润输入计算，零订单的商品最多 3/8 = 0.375 | Owner 决定：策略最低完整度先设 0.375 |
| 经济性 profile | 没有任何接口能创建 profile，应用的数据库角色只有读权限，也没有核验流程 | 第二部分 |
| 建议的版本摘要 | 摘要取自建议提出时的指标输入；之后有新观测，审批时就会报 `ENTITY_VERSION_CHANGED` | 本 PR |

Owner 2026-10-01 的四项决定：

1. 平台不采集的退货、财务费用、广告，按 Owner 声明“本店目前没有”记录；出现订单后，退货和财务费用的声明自动失效；
2. 策略最低数据完整度先设 0.375，有订单后再调高；
3. 经济性 profile 按店铺 + FBS，每项取全店各商品的最高费率；仓储、促销、退货损失、广告标为不适用；两位 Owner 核验；
4. 单件目标利润和安全缓冲都设为 0 卢布，以 15% 利润率下限为准。

### 本 PR

- **新鲜度水位**（`FeedWatermarkKeeper`）：每轮定时采集告一段落后、重算之前，按各数据源的依据写入已核验的水位。依据变化时才写；按“重新确认”写的那几项，最多每 12 小时写一次。
  - 价格、库存：最近一次采集并标准化成功的运行，生效时间为采集完成时间；
  - 销售：最近一次下单件数采集的窗口截止时间；
  - 内部成本：最近一次“价格采集后主数据自动化已核对成本”的运行；
  - 商业输入：单件目标利润和安全缓冲都有生效的财务输入时，按“重新确认”写；
  - 退货、财务费用：有生效声明且下单件数为 0 时，生效时间为下单数据的截止时间；出现下单就把声明标为失效（`ORDERED_UNITS_APPEARED`），不再续写；
  - 广告：有生效声明时，按“重新确认”写；
  - 不再续写的数据源，会在策略规定的时长后过期，护栏随之拦截。
- **数据源声明**（V0029 `ops.feed_absence_attestation`）：
  - `POST /api/v1/console/stores/{storeId}/feed-attestations` 提交，`POST …/{id}/revocation` 撤销，都需要 `COMMERCIAL_POLICY_MANAGE` 和近期登录；
  - 默认有效 30 天，最长 90 天；
  - 近 30 天有下单时，不接受退货、财务费用的声明。
- **查看新鲜度**：`GET /api/v1/console/stores/{storeId}/feed-freshness`（`DIAGNOSTIC_VIEW`），返回 8 个数据源的生效时间、年龄和依据，以及声明列表。
- **页面**：“数据采集”页新增“护栏数据新鲜度”，包括数据源表、“声明不适用”和声明列表（可撤销）。
- **建议随事实更新**：在审的调价建议如果依据的指标输入已经变化（摘要与当前不符），即使价格和数字都没变也会作废重提，原因 `SUPERSEDED_BY_NEWER_CALCULATION`，避免审批时被 `ENTITY_VERSION_CHANGED` 拦下。
- **履约方式**：试点店铺已声明为 FBS（`SELLER_FULFILLED`，2026-10-01 03:30 UTC 起），通过本机管理接口，操作人 `claude-for-owner`。

### 试点（2026-10-01）

- 后端重启后的第一轮写入了价格、库存、内部成本（截至 00:10 UTC 的采集）和销售（截至 09-30）四个水位；
- Owner 声明退货、财务费用、广告“本店目前没有”（有效至 10-31）后的下一轮，写入了退货、财务费用（截至 09-30，下单件数为 0）和广告（重新确认）三个水位。8 个数据源现在有 7 个有水位，商业输入要等第三部分录入财务输入；
- Owner 点“重新计算诊断”后，7 条在审建议因指标输入摘要变化作废重提，新的 7 条带当前摘要；
- 护栏预览仍只剩原来的三个原因（没有策略、没有 profile、缺两个指标），留给第二、三部分。

## 控制台：店铺诊断

控制台首页（导航里的"店铺概览 → 店铺诊断"，路径 `/store/diagnosis`）按商品汇总平台给出的各项信号，用来回答"为什么卖不动"。

- **数据**：后端接口 `GET /api/v1/console/stores/{storeId}/diagnosis`，需要 `DIAGNOSTIC_VIEW` 加店铺范围，读取会记审计。每个商品取以下几类信号的最新一条事实：
  - 可见性：来自 LISTING_HEALTH；
  - 库存：最新快照，按履约方式合计；
  - 价格和价格指数：来自 PRICE；
  - 内容评分：来自 LISTING_CONTENT；
  - 搜索人数和前 5 个搜索词：来自 LISTING_SEARCH / LISTING_SEARCH_TERM，所有商品都取店铺最近的同一个统计期间，便于横向比较；
  - 近 7 天下单件数：以店铺最近一个有流量数据的 UTC 日为终点，往前算 7 天。

  每个信号都带数据时间；没有采集到的信号显示"未采集"或"无记录"，不会显示成 0。
- **页面**：
  - 顶部汇总：不可见、无库存、价格指数 RED/YELLOW、有搜索需求、有搜索无下单、近期有下单的商品数，以及内容评分均值；
  - 商品表格：可以筛选（只看有问题的、不可见、无库存、价格 RED、有搜索无下单、有下单）和搜索，库存、价格竞争力、评分、搜索人数、下单这几列可以排序；
  - 点击一行打开详情抽屉，其中"搜索需求"一节列出搜索人数、统计期间和主要搜索词（俄语原文，不翻译）。
- **价格竞争力**：是 Ozon 自己匹配竞品得出的判断，页面上标注"C 级 · 平台分析"，只用于诊断，不驱动调价。"比 Ozon 最低竞品价贵 x%"用含卖家促销价（没有时用不含促销价）计算，而且只在两个价格币种相同时才算。
- **诊断结论**：规则结论、预估利润率和内容评分下限见上面的 P3。
- **不做**：暂不跳转到其他区域。
- **本地查看**：控制台固定操作 `frontend/marketops-console/.env.local` 里 `VITE_MARKETOPS_STORE_ID` 指定的店铺，要看 Ozon 试点店铺就填它的店铺 ID，然后重启 `make frontend-dev`。控制台的登录状态只保存在内存里，刷新页面或直接改地址栏都需要重新登录。

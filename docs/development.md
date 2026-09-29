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

- 出站白名单（`application-local.yaml` 的 `platform:OZON:read`）每个读取接口单独一条规则，目前放行 `/v1/roles`、`/v4/product/info/attributes`、`/v5/product/info/prices`、`/v4/product/info/stocks`、`/v1/analytics/data`、`/v3/product/info/list`、`/v1/product/rating-by-sku` 和 `/v1/analytics/product-queries`（这条同时覆盖 `/details`，因为一个调用只能命中一条规则，前缀不能重叠）。后续每接入一个读取接口，就单独加一条规则，并完成该接口的核验。漏加规则时，运行会以 `request_could_not_be_built` 停在 `BLOCKED`。
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
- **不做**：内容评分不设"偏低"阈值（阈值属于策略，需要 Owner 决定）；也暂不跳转到其他区域，因为试点商品还没有映射到内部 SKU。
- **本地查看**：控制台固定操作 `frontend/marketops-console/.env.local` 里 `VITE_MARKETOPS_STORE_ID` 指定的店铺，要看 Ozon 试点店铺就填它的店铺 ID，然后重启 `make frontend-dev`。控制台的登录状态只保存在内存里，刷新页面或直接改地址栏都需要重新登录。

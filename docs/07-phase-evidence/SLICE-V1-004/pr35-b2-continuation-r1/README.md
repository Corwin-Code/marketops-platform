# SLICE-V1-004 — PR #35 B2 continuation (R1)

Continuation of supplement `S4-PR35-45474B60-RESIDUAL-SUPPLEMENT-01` §B2 (fresh-clone full entry), performed by
`CLAUDE_OPUS_5` on 2026-09-22 under the external handoff `02_PR35_B2_CONTINUATION.md` (SHA-256
`635a47cc0a63688d2136c1e4aca905d303ed8cf007a6411abf83473b4581badd`). It grants nothing new. The earlier record
[`../pr35-residual-supplement-r1/`](../pr35-residual-supplement-r1/) is unchanged.

| Identity | Commit | Tree |
|---|---|---|
| Continuation start (published Head) | `1fa72cacf3a7c1e7167d809e9ffd9daf67c1cab5` | `79dc40057649fb7d3a6483200f797dace03c2dff` |
| Implementation checkpoint F (tested) | `e0ee75e2511e855df6a09cdf7d45170bae2fe534` | `cd262ca8f5c4a58e675941dd7079369c9d6db29d` |
| This documentation successor G | the commit that adds this file (parent F) | — |

## Owner directions in this session (transcribed, not signed)

1. Port conflict. Another project's Java process held `*:8080` (IPv6 dual-stack) on the host; the entry's
   `verify_local_config.sh` polled a hardcoded `127.0.0.1:8080` and the browser stage bound it. Asked how to
   proceed, the Owner answered: "切换成9999端口，继续运行"; on host load: "View Turbo 是不可能退出的，在不退出的情况下，执行最大能力范围的操作".
2. After the run below failed, the Owner said "现在这完全是放大了工作量，完全不符合Owner我的预期" and, asked what to do
   with the local full-entry proof of 05 C.5/C.8, chose: "停止本地 full，据实交回 (推荐)" — no further local rerun;
   hand back this failure evidence and the port fix, with the CI of the published Head as the regression evidence.
   The Owner added: "功能都未完成，产品现有实现还未集成真实模型和平台api很多都是未验证甚至可能随时推翻的，做这么多验证完全是无用功，重心完全错了".

## Change in F (port only)

The backend stages of `scripts/fresh_clone_check.sh` use a dedicated `HTTP_PORT=9999` passed as `SERVER_PORT`;
`verify_local_config.sh` and `business_browser_isolated.sh` read `SERVER_PORT` (unset: 8080, empty/invalid:
refused) and refuse a port unless it actively refuses a TCP connection (fail-closed probe); the browser suite
resolves its backend origin from the same value (`tests/browser/backendOrigin.ts`, `playwright.config.ts`, five
specs). `validate_production_readiness.py` binds this; `BackendPortIsolationTests` adds 10 tests. Developer
default 8080, `init_local_env.py`, `application.yaml`, the Makefile and CI are unchanged; no SQL, schema or
production code. Local checks at F: both validators pass, 450 Python tests, frontend lint/format/typecheck and
`test:ci` 429 tests pass.

## Real run at F — not completed

`local-verification/run-e0ee75e2511e/` (runner, observer and raw output). 2026-09-22 03:12:31–03:18:51 UTC,
macOS 26.6.2 arm64, Java 21.0.10, Node 24.19.0, Docker 29.7.2 (VM 4 CPUs).

| Stage | Result |
|---|---|
| Clone into `MarketOps clone's verification`, ignored-state check | passed |
| Governance, readiness, 450 validator tests in the clone | passed |
| env-init, dedicated database port | `marketops-fresh-e0ee75e2511e-9ba94746237f` on `127.0.0.1:56929` |
| `./mvnw -B -ntp verify` | Surefire 1922 / 0. Failsafe: 277 run, 3 failures and 94 errors, all on database connection acquisition (`PSQLException: 嘗試連線已失敗`, `Failed to obtain JDBC Connection`) |
| Frontend, coverage negatives, supply chain, `verify_local_config.sh` on 9999, isolated browser | not reached |

Cause: host ephemeral-port exhaustion. `host-samples.tsv.gz` shows ports in use rising from 5 240 (03:13:02) to
16 354 and 16 378 of 16 384 (03:17:05, 03:17:36) — the window of the failures — and 4 985 at 03:18:06. By
`netstat -anv` the sockets belonged to the host's proxy `ViewTurboCore` (about 5 900 loopback pairs to its own
port 15732 and 5 800 tunnel connections), not to this run. Once the outcome was fixed, the run's Maven processes
were stopped at 03:18:48 (exit 143) so the entry's own exit trap ran.

Cleanup: the entry removed its Compose project and workspace; ports 9999/8082/4173 free afterwards; the Docker
snapshot taken after the Testcontainers reaper equals the one before the run. `generated-config-proof.txt`: the
six generated local database passwords (three per generation) occur 0 times in the 205 plain-text evidence files;
they existed only in the observer's memory. (`VITE_MARKETOPS_OIDC_TOKEN_ENDPOINT` is listed as "secret" only by
name and is empty.)

## Not run, residuals

- A completed local fresh-clone full run: not obtained and, by the Owner's direction, not retried. The CI of the
  published Head is the complete regression; its results are reported in PR #35.
- `verify_local_config.sh` probes its port once before starting the backend; a process that starts listening on
  that port in the following seconds could still answer readiness (narrowed by F, not closed).
- `scripts/validation/advertising_browser_isolated.sh` and the Makefile browser targets still use fixed 8080.

No Secret, credential or Buyer PII is recorded. V0001–V0124, frozen Contract/Annex/Finding Set, Owner and
Controller originals, 27/27 and 54/12/3 are unchanged. No Controller PASS is claimed; PR #35 stays Draft/Unmerged.

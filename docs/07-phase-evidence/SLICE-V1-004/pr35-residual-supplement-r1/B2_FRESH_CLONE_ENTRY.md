# B2 — Fresh-clone full entry off the default database port

Residual: `PR35-FRESH-CLONE-ENTRY` (Controller record, `residuals`). Scope: supplement §B2.

## Root cause

Full mode ran `make env-init`, which writes `MARKETOPS_DB_PORT=5432`, started the Compose stack on
`127.0.0.1:5432` and ran `npm run test:browser`, whose `BrowserFixtureApplication` refuses port 5432 by design.
The same refusal had already been fixed for CI by `scripts/validation/business_browser_isolated.sh`.

## As built (`scripts/fresh_clone_check.sh`)

| Stage | Behaviour |
|---|---|
| Before any stack | Refuses inherited `MARKETOPS_DB_*`, `MARKETOPS_POSTGRES_*`, `SPRING_DATASOURCE_*`, `SPRING_FLYWAY_*`, `SPRING_CONFIG_*` and `SPRING_APPLICATION_JSON`, the same list as the isolated entry. |
| Project names | `marketops-fresh-<commit12>-<random12>` for the configuration stack and `marketops-browser-fresh-<commit12>-<random12>` for the browser stack; either is refused if it already owns a container, volume or network. A Docker listing error is a failure, never "no resources". |
| Configuration stage | `make env-init` and `dev_doctor` as before; the one generated `MARKETOPS_DB_PORT=5432` line is checked and moved to a freshly allocated loopback port; `make up`; the published port must be exactly `127.0.0.1:<port>`. Backend verify, frontend, coverage negatives, supply chain and `verify_local_config.sh` then run against that one instance. |
| Hand-over | The configuration stack is removed, the script checks that its project owns nothing, and deletes only the two `.env.local` files this run generated. |
| Browser stage | Chromium is installed; `business_browser_isolated.sh` then generates, moves, starts, checks, tests and removes its own instance under the browser project. If it fails while its stack exists it keeps its file and the exit trap removes the same project with the same credentials. Afterwards no resource of that project and no generated file may remain. |
| Unchanged | The developer default port in `scripts/init_local_env.py`, the special clone path (whitespace and a single quote), the ignored-state check, `--offline`, every fixture guard, non-zero exits. |

`scripts/validate_production_readiness.py` binds both entries (`FRESH_CLONE_CONTRACT_TOKENS`,
`FRESH_CLONE_PROHIBITED_TOKENS`, `ISOLATED_BROWSER_CONTRACT_TOKENS`) and extends the path-wording scan to the
isolated entry; `FreshCloneEntryContractTests` (9) mutate each bound behaviour, including through
`check_repository_contracts` itself.

## Real run

One attempt at the implementation checkpoint `3bcc38fafa2760fe59c2144fe54887e212044d30`, 2026-09-18
22:37–23:35 UTC, through `local-verification/b2-fresh-clone-3bcc38fafa27/runner.sh` (macOS arm64; `/bin/bash`
3.2.57 and bash 5.3.9; Java 21.0.10; Node 24.19.0 / npm 11.17.0; Docker 29.7.2). Raw log, per-class summary,
Docker snapshots and host state are in that directory.

**Result: the entry did not complete (exit 1). It stopped in backend verify on host connection failures.**

| Stage | Observed |
|---|---|
| Clone into `MarketOps clone's verification` (whitespace and a single quote) | passed |
| No ignored state; governance, readiness and validator tests in the clone | passed |
| env-init, `dev_doctor`, dedicated port | stack `marketops-fresh-3bcc38fafa27-d25420fa980a` published on `127.0.0.1:65193`, not 5432; published-port check passed |
| `./mvnw -B -ntp verify` | Surefire 1922 / 0. Failsafe 1381 with 2 failures and 34 errors in six classes; coverage checks met |
| Frontend, coverage negatives, supply chain, `verify_local_config.sh`, teardown, isolated browser stage | not reached (`set -e`) |
| Exit trap | removed the configuration stack |

Every description suite passed inside this run: `ListingDescriptionProviderWaitTransportIT` 11/11,
`ListingReworkAuthorizationIT` 111/111, `ListingDescriptionResponseIdentityIT` 21/21,
`ListingDescriptionRetryTimingIT` 7/7 and `ListingDescriptionRegistryIT` 7/7.

The six classes that did not pass (`fresh-clone-full.test-summary.tsv`):

- 27 errors are `Failed to obtain JDBC Connection` or "connection attempt failed": `ListingActionLaunchIT` 7,
  `ListingDescriptionWriteGateIT` 8, `RegistryVerificationFlowIT` 7, `AdvertisingMinimumExpiryIT` 2 and
  `AvailabilityCaseLifecycleIT` 3.
- The remaining 7 errors and 1 failure are in `AvailabilityCaseLifecycleIT` after its own connection failures
  (missing rows, an empty optional, a null SLA, a refused transition, a refusal not raised); the class passed
  25/25 when run alone.
- `RepresentativePerformanceIT` exceeded its 300 000 ms runtime bound at 307 782 ms.

Run alone at identical code (`failed-classes-rerun.log.gz`): `AvailabilityCaseLifecycleIT` 25/25,
`ListingActionLaunchIT` 25/25, `ListingDescriptionWriteGateIT` 8/8 and `AdvertisingMinimumExpiryIT` 17/17
passed; `RegistryVerificationFlowIT` failed again, all 26 errors on connection acquisition.
`RepresentativePerformanceIT` was not re-run.

Host state (`loopback-environment.txt`): about 17 000 TCP sockets, of which about 10 400 are loopback
connections of a third-party process (`ViewTurboCore-un`) to its own port 15732, against an ephemeral range of
16 384 ports. The failing classes open a new JDBC connection per statement. The same machine passed a full
clean verify on 2026-09-18 (`../pr35-ci-evidence-correction-r1/local-verification/`). The process belongs to
the host, not to the repository, and was not changed.

Docker snapshots: no container, volume or network that existed before the run was removed, and the snapshot
taken after the Testcontainers reaper finished is identical to the one taken before the run.

The full chain therefore still needs one completed local run; the CI of the published Head remains the complete
regression. Any later attempt is reported in PR #35 and the external handoff, not here.

## Reported, not changed

- The outer script has no INT/TERM handler; a signal sent only to it can run the exit trap while a child
  (Compose or Playwright) continues. This existed before B2.
- The Makefile target `frontend-browser` still runs `npm run test:browser` against the default configuration
  and is refused by the fixture by design; it is outside this entry.

# Local verification

**Result: PASS**

Execution date: 2026-08-16. Implementation Head:
`4001a8d2717739967bf48a71c6a4f82bd2e5c50f`. Evidence records commands,
versions and aggregate results, never environment values, local user paths,
Testcontainers ports or raw credentials.

## Toolchain

| Tool | Version |
| --- | --- |
| macOS | 26.6.1 (25G76), arm64 |
| Java | Azul OpenJDK 21.0.10 LTS |
| Maven Wrapper distribution | 3.9.16; Wrapper 3.3.4 |
| Node | 24.19.0 |
| npm | 11.17.0 |
| Docker Engine / Compose | 29.7.2 / 5.3.1 |
| PostgreSQL image | 18.4 |
| Python | 3.9.6 |
| Git | 2.50.1 |
| Playwright / Chromium | 1.62.1 / 151.0.7922.34 |

## Backend

| Command | Result |
| --- | --- |
| `./mvnw -B -ntp -DskipITs verify` | PASS |
| `./mvnw -B -ntp -Dtest='*ArchitectureTest' -DfailIfNoTests=true test` | PASS, 28 tests in exactly 3 Surefire suites |
| `./mvnw -B -ntp verify` | PASS, 100 unit/configuration/architecture + 22 integration tests |
| negative JaCoCo threshold mutation | PASS; the deliberately impossible threshold failed for coverage |

JaCoCo totals: lines 135/135 (100%), branches 32/34 (94.12%), methods 40/40
and classes 13/13. The contaminated-migration rollback, eight foundation
schemas, role ownership, application-role restrictions, `PUBLIC` revocation and
cluster-authority restrictions all passed. Captured log tests assert no throwable
proxy and no credential, host, port, role, SQL or exception-message marker; the
final public CI logs contain none of those injected markers.

## Frontend

| Command | Result |
| --- | --- |
| `npm ci` | PASS, 375 packages from the committed lockfile in the clean clone |
| `npm ls --all` | PASS; displayed unmet entries are optional platform/peer packages |
| `npm run lint` | PASS |
| `npm run format:check` | PASS |
| `npm run typecheck` | PASS |
| `npm run test:ci` | PASS, 45 tests in 7 files |
| `npm run build` | PASS, production bundle |
| `npm run verify:bundle` | PASS, only prefixed values reached the bundle |
| `npm run test:browser` | PASS, one built-console Ready → Degraded → Ready scenario |
| negative Vitest threshold mutation | PASS |

Frontend coverage: statements 128/135 (94.81%), branches 78/91 (85.71%),
functions 27/29 (93.10%) and lines 126/131 (96.18%). Polling tests cover the
normal interval, three bounded retries, no overlap, cancellation, manual refresh
and React StrictMode cleanup.

## Repository, runtime and supply chain

The following required commands passed: `make bootstrap`, `make up`,
`make verify-local-config`, `make supply-chain`, full special-character
`scripts/fresh_clone_check.sh`, final `make down`, `git diff --check` and clean
Git status. Bootstrap preserved an existing complete ignored configuration and
rejects a partial pair.

Backend and frontend SBOMs validate as CycloneDX 1.6 with 76 and 341 components.
Licence inventories cover 130 Maven dependencies and 366 installed npm packages;
no installed npm package is undeclared. The Python governance/readiness suite
passed 104 tests. The three global hard rules passed.

Controller's four actionable log warnings are retired. Remaining upstream
output is explicitly recorded in `ci-checks.md`; no warning was suppressed merely
to make a Gate green.

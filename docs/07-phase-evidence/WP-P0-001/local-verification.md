# Local verification

**Result: PASS**

Execution date: 2026-08-15. The evidence is sanitized: it records commands,
versions and aggregate results, not environment values, local paths, user names,
ports selected by Testcontainers, or raw logs.

## Toolchain

| Tool | Version |
| --- | --- |
| Java | 21.0.10 |
| Maven Wrapper distribution | 3.9.16; Wrapper 3.3.4 |
| Node | 24.19.0 |
| npm | 11.17.0 |
| Docker Engine / Compose | 29.7.2 / 5.3.1 |
| PostgreSQL image | 18.4 |
| Python | 3.9.6 |
| Git | 2.50.1 |

## Backend

| Command | Result |
| --- | --- |
| `./mvnw -B -ntp -DskipITs verify` | PASS |
| `./mvnw -B -ntp -Dtest='*ArchitectureTest' -DfailIfNoTests=true test` | PASS, 18 tests |
| `./mvnw -B -ntp verify` | PASS, 87 unit/configuration/architecture + 21 integration tests |
| effective POM and dependency tree | PASS; dependency convergence PASS; Testcontainers 2.0.5 resolved |
| negative JaCoCo threshold mutation | PASS; the deliberately impossible threshold failed for coverage |

JaCoCo totals from the full verify: lines 122/122 (100%), branches 32/34
(94.12%), methods 38/38, classes 13/13. The contaminated-migration rollback,
eight foundation schemas, role ownership, application-role restrictions,
`PUBLIC` revocation and cluster-authority restrictions all passed.

## Frontend

| Command | Result |
| --- | --- |
| `npm ci` | PASS, 375 packages from the committed lockfile in a clean clone |
| `npm ls --all` | PASS; unmet entries are optional platform/peer packages |
| lint, format check, type-check | PASS |
| `npm run test:ci` | PASS, 39 tests in 7 files |
| build and bundle-isolation canary | PASS |
| negative Vitest threshold mutation | PASS |
| `npm audit --audit-level=high` | PASS, 0 vulnerabilities at every severity |

Frontend coverage: statements 93/94 (98.93%), branches 58/65 (89.23%),
functions 23/23, lines 92/93 (98.92%). Clean installation reported lifecycle
scripts for `fsevents` and `libxmljs2`; both were reviewed and explicitly denied
through `allowScripts`. Runtime and JSON SBOM generation do not require those
optional native build steps.

## Runtime and supply chain

`make bootstrap`, `make up`, `make verify-local-config`, `make supply-chain`,
and the automated Playwright acceptance passed. The backend and frontend SBOMs
validate as CycloneDX 1.6 with 76 and 244 components. Licence inventories contain
130 Maven dependencies and 254 installed npm packages; no installed npm package
has an unknown licence. MPL/EPL/LGPL-or-permissive dual-licence entries are
weak/file-level or offer a permissive choice; no strong/network-copyleft-only
or unknown case requires an Owner decision.

The Python governance suite passed 86 tests. Workflow YAML, shell syntax,
Python compilation and `git diff --check` also passed.

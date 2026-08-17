# Local verification

**Result: PASS**

Execution date: 2026-08-17. Implementation Head
`a971717a658e9db315c5e6c3e03e5b5899e48f65`; tree
`2f227c35b515a21b8e412a0adea59838dbfc5af8`. Evidence records commands,
versions and aggregate results, never environment values, local user paths,
Testcontainers ports or raw credentials.

## Toolchain

| Tool | Version |
| --- | --- |
| macOS | 26.6.1 (25G76), arm64 |
| Java | Azul OpenJDK 21.0.10 LTS |
| Maven Wrapper distribution | 3.9.16; Wrapper 3.3.4 |
| Node / npm | 24.19.0 / 11.17.0 |
| Docker Engine / Compose | 29.7.2 / 5.3.1 |
| PostgreSQL image | 18.4 |
| Python / Git | 3.9.6 / 2.50.1 |
| Playwright / Chromium | 1.62.1 / 151.0.7922.34 |

The host default Node 22 correctly failed the repository's Node `>=24` engine
check. Every certified frontend command was rerun through Node 24 and passed.

## Governance and project hard rules

| Command | Result |
| --- | --- |
| `python3 scripts/validate_governance.py` | PASS |
| `python3 scripts/validate_production_readiness.py` | PASS; all three global checks |
| `python3 -m unittest discover -s tests -p 'test_*.py'` | PASS, 133 tests |

Authorization mutations reject `NONE` with an open authorization, an active WP
with `PLANNING_ONLY`, candidate/non-closed completion, ambiguous fields, missing
historic/result records and D-03 falsely marked `VERIFIED`. Canonical state is
`active_work_package: NONE`, `authorization: PLANNING_ONLY`; WP-P0-001 is
`COMPLETED`, authorization `CLOSED`, result `VERIFIED`, while the historic design
verdict remains `APPROVED_FOR_IMPLEMENTATION`. The D-03 Modular Monolith portion
is verified here; the PostgreSQL Task/Outbox Worker is explicitly assigned to
WP-P0-003 outside this WP's scope and remains `ACTIVE_CONTROL`. The canonical
backlog independently records WP-P0-001 as `COMPLETED`; its structural parser
rejects missing, duplicate, stale or unknown-state rows. WP-P0-002 remains
`DRAFT`, so closure did not activate new work.

## Backend

| Command | Result |
| --- | --- |
| `./mvnw -B -ntp -DskipITs verify` | PASS, 110 tests |
| `./mvnw -B -ntp -Dtest='*ArchitectureTest' -DfailIfNoTests=true test` | PASS, 31 observations in exactly 3 suites |
| `./mvnw -B -ntp clean` then `./mvnw -B -ntp verify` | PASS, 110 unit/configuration/architecture + 22 integration tests |
| `scripts/verify_coverage_thresholds.sh backend` | PASS; impossible 100% branch threshold was rejected |

JaCoCo totals are 170/170 lines (100%), 46/50 branches (92.00%) and 65/69
methods. The architecture run covers seven approved boundary
factories, eleven invalid observations, positive fixtures, and independent
Spring Modulith verification.

The CI profile's real Spring Boot `StructuredLogEncoder` produced parseable ECS
JSON with timestamp, level, message, application, environment, build version and
exactly one root correlation ID; request/application records preserved event,
error code and exception class while system records deterministically used
`correlationId=none`. Markers, duplicate context, `error` and stack data were
absent. The local `PatternLayout` produced one readable line with the
same safe identity/event fields. Validation logs are WARN, missing-resource logs
INFO and unexpected failures ERROR. Repeated DB/Flyway degradation emits one WARN
until recovery, then rearms. Captured tests prove no throwable proxy, exception
message, credential, host, port, role or SQL marker is logged.

`ApplicationEnvironmentFailClosedTest` configured every unrelated prerequisite
and proved an unprofiled context fails specifically because
`marketops.environment` is absent, without exposing configuration values.

## Frontend

| Command | Result |
| --- | --- |
| `npm ci` | PASS, 375 packages from the committed lockfile |
| `npm ls --all` | PASS; reported unmet entries are optional platform/peer packages |
| `npm run lint` / `npm run format:check` / `npm run typecheck` | PASS |
| `npm run test:ci` | PASS, 53 tests in 8 files |
| `npm run build` / `npm run verify:bundle` | PASS |
| `npm run test:browser` | PASS, one built-console Ready → Degraded → Ready scenario |
| `scripts/verify_coverage_thresholds.sh frontend` | PASS; impossible 100% thresholds were rejected |

Frontend coverage: statements 128/135 (94.81%), branches 78/91 (85.71%),
functions 27/29 (93.10%) and lines 126/131 (96.18%). The displayed frontend
version is the validated package version `0.1.0`; the browser build and assertion
call the same source-identity resolver. CI accepts only the explicit lowercase
40-hex authored Head and fails if it is absent; local execution alone may resolve
the repository Head. A checkout merge SHA and transient ref are prohibited as
published source identity.

## Runtime, Fresh Clone and supply chain

`make bootstrap`, `make up`, `make verify-local-config`, `make supply-chain`,
the complete special-character `scripts/fresh_clone_check.sh`, final `make down`,
`git diff --check` and clean Git status all passed. A sandbox-only Chromium Mach
port refusal was rerun outside the sandbox; the identical Head passed from start
to finish and scoped Docker resources were absent afterward.

Backend and frontend SBOMs validate as CycloneDX 1.6 with 76 and 341 components.
Licence inventories cover 130 Maven dependencies and 366 installed npm packages;
no installed frontend licence is undeclared.

Preserved upstream output: CycloneDX emits `meta:enum` and `deprecated` schema
keyword warnings; npm reports transitive deprecation notices for
`prebuild-install` and old `glob` plus platform/peer optional dependencies. These
were recorded, not suppressed to claim a zero-warning build.

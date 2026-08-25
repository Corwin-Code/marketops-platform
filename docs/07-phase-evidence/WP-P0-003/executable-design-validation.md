# WP-P0-003 executable design validation evidence

```yaml
task: WP_P0_003_EXECUTABLE_DESIGN_VALIDATION_F02_FINAL_TARGETED_REWORK
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: 937245514881dec580d3f4f6651e94da44536dde
reviewed_input_tree: fd2e6b0bb3330a9e02a16f440aa424de0ca7c1d2
verified_implementation_head: d93b931c7d2d278ed494ea632cada7f165144b0b
verified_implementation_tree: 3af7ef725420b5373556676a7a46cd52ffec9470
final_package_identity: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
targeted_findings: WP3-EDV-F02-R1A, WP3-EDV-F02-R1B, WP3-EDV-F02-R1C
preserved_closed_findings: WP3-EDV-F01, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
environment: local workstation, Docker, Testcontainers, postgres:18.4
design_approved: false
targeted_rework_status: IMPLEMENTED_AWAITING_CONTROLLER
bounded_scope_quality: PRODUCTION_GRADE
project_production_complete: false
marketplace_outbound: NONE
production_write: DISABLED
```

## Finding-to-test matrix

| Finding | Required proof | Result |
| --- | --- | --- |
| `F02-R1A` | One post-epoch-lock database instant for subject, scope, Credential and temporal boundary; old/new Credential at fixed activation; no missing interval; future scope-grant before/after; helper not executable by app | PASS — `TC-CTRL-434/435`, two 201-point REAL_DATABASE property samples, ACL assertion |
| `F02-R1B` | Explicit attached-cycle detection; complete linear chain success; disconnected branch, multiple leaves and `STORE_SET` deny without residue | PASS — `TC-CTRL-430/432/436/437/438` |
| `F02-R1C` | Exact sole acquisition executor, exact JDBC grant mapper and exact request factory; violations both outside and inside owning module; conforming fixture | PASS — `TC-ARCH-021/023/024`, `F-ARCH-021/021I/023/023I/024/025` |
| Preserved `F01` | Writer-first/grant-first metadata serialization and zero-residue denial | PASS — `TC-CTRL-F01-A/B/C1/C2`, `TC-CTRL-420…422` |
| Preserved `F03` | Final checkpoint CAS after blocking and lease expiry | PASS — `TC-CTRL-F03-A/B`, `TC-CTRL-406…409/427/428` |
| Preserved `R01` | Functional migration contract and source-comment scan | PASS — `TC-CTRL-302…304`, `TC-GLOBAL-002` |
| Preserved `RR02` | Separate reviewed input, implementation and final-package identities | PASS — this package plus live PR #16 body |

## Backend verification

`./mvnw -B -ntp verify` completed successfully against PostgreSQL 18.4:

- 10 Flyway migrations validated and applied from an empty schema;
- 197 unit and architecture tests passed;
- 146 integration tests passed;
- 343 total tests passed with zero failures/errors/skips;
- `IngestionAuthorityArchitectureTest`: 13/13 passed;
- `CallAuthorityExclusivityIT`: 15/15 passed;
- `IngestionAuthorityAndEvidenceIT`: 29/29 passed;
- `AuthorizedAcquisitionFlowIT`: 2/2 passed; and
- all JaCoCo coverage checks passed.

The complete-command summary is recorded in `backend-verify-run.txt`; targeted
R1A/R1B/R1C observations are recorded in `f02-final-targeted-tests-run.txt`.

## Exact function and ACL surface

| Object | `PUBLIC` | `marketops_app` | Authority posture |
| --- | --- | --- | --- |
| `platform.grant_call_authority(uuid,bigint,text,uuid,interval,text)` | all revoked | `EXECUTE` | only call-authority transition/evidence writer; `SECURITY DEFINER`, fixed `search_path` |
| `platform.evaluate_call_control_facts(uuid,uuid,timestamptz)` | all revoked | none | deterministic production resolver only; no lock, transition, evidence or authority return |
| `ops.acknowledge_checkpoint(uuid,bigint,text,uuid,bigint,text)` | all revoked | `EXECUTE` | only checkpoint transition writer; `SECURITY DEFINER`, fixed `search_path` |
| run/checkpoint/decision evidence tables | none | `SELECT` | no direct app `INSERT`, `UPDATE` or `DELETE` |
| three Raw evidence tables | none | `SELECT`, `INSERT` | no app `UPDATE` or `DELETE` |

## R1A deterministic database observations

| Scenario | Fixed observations | Result |
| --- | --- | --- |
| Credential successor | at `T−1ms`, old Credential selected and `valid_until=T`; at `T`, successor selected | PASS |
| Credential missing-interval mutation | 201 instants over `T±1s`, every 10ms | no instant selected old with `valid_until>T` |
| Future scope-grant | at `T−1ms`, `valid_until=T`; at `T`, the future-start boundary no longer caps the snapshot | PASS |
| Scope missing-interval mutation | 201 instants over `T±1s`, every 10ms | every instant before `T` had `valid_until<=T` |
| Resolver ACL | `has_function_privilege(marketops_app, ..., EXECUTE)` | `false` |

Production order inspected in V0010:

```text
four epoch locks → evaluated := clock_timestamp()
→ subject/scope/Credential/control_snapshot_temporal(..., evaluated)
→ grant_at := clock_timestamp()
→ grant_at < valid_until
→ final lease guard uses grant_at
```

No JVM clock participates in database authority evaluation.

## R1B Credential graph observations

| Graph | Acceptance metrics/outcome | Residue |
| --- | --- | --- |
| `A ↔ B`, `C → A` | repeated UUID is emitted with `is_cycle=true`; `MO013` | run `LEASED`, call sequence `0`, evidence `0` |
| `C → B → A` | one leaf, zero cycles, all candidates reachable; C selected | grant succeeds; evidence names C |
| linear component plus disconnected cycle | reachable count differs from candidate count; `MO013` | zero authority residue |
| two independent leaves | leaf count is two; `MO013` | zero authority residue |
| only account credential changed to `STORE_SET` | no eligible ACCOUNT candidate; `MO013` | zero authority residue |

The resolver uses `UNION ALL` plus `visited_path` and `is_cycle`; it does not
use recursion termination or `UNION` deduplication as cycle evidence.

## R1C architecture observations

| Protected operation | Allowed class | Sensitivity fixtures |
| --- | --- | --- |
| call `AcquisitionPort.acquire` or concrete implementation | `AuthorizedAcquisitionExecutor` | outside interface caller; inside-owner concrete-adapter caller |
| construct `CallAuthorityGrant` | `CallAuthorityGrantMapper` | outside rebinder; inside-owner untrusted rebinder |
| call `AcquisitionRequest.from` | `AuthorizedAcquisitionExecutor` | second package-private factory caller |

The production mapper consumes only named columns of the structured database
grant result. The conforming fixture implements the port but obtains the sole
executor instead of calling its own method. All 13 architecture and mutation
tests pass.

## Cross-checks

- `git diff origin/main -- V0001…V0006`: empty.
- `git diff --check`: pass.
- `python3 scripts/validate_governance.py`: pass.
- validator unit suite: 243/243 pass.
- `TC-GLOBAL-001` Compromise Retirement Check: pass.
- `TC-GLOBAL-002` Functional JavaDoc Rewrite Check: pass.
- `TC-GLOBAL-003` Production Naming Check: pass.
- no secret/PII, credential retrieval or real Marketplace outbound path added.
- no Dependabot change mixed into PR #16.

## Controller principles audit

| # | Assessment |
| --- | --- |
| 1 | PASS — governance, active WP, ADRs, Controller ruling, V0010, Java authority types, tests and live PR/CI facts were cross-checked. |
| 2 | PASS FOR TARGETED SCOPE — all three findings have deterministic executable proof; no known production blocker remains in this bounded authority path. |
| 3 | PASS FOR TARGETED SCOPE — the changes are fail-closed production implementations, not a minimum vertical slice; project-level runtime remains explicitly outside this Gate. |
| 4 | PASS — remaining work-package/project Deferred Items are enumerated in the addendum and are not represented as delivered. |
| 5 | PASS — no time seam, cycle heuristic, module-wide exception or fallback path remains in the repaired boundary. |
| 6 | PASS — production decisions were made autonomously from the ruling and existing ADRs; no Owner decision was required. |
| 7 | PASS — changed production comments describe current functional capability only; `TC-GLOBAL-002` passes. |
| 8 | PASS — multi-clock evaluation, dedup-recursion cycle inference and broad owner-module authority were replaced, not retained in parallel. |
| 9 | PASS — all three global hard rules pass. |
| 10 | BOUNDED PRODUCTION-GRADE / PROJECT INCOMPLETE — the executable authority is production-grade; WP-P0-003 and MarketOps are not project-level production-complete. |
| 11 | YES — project-level deferred/readiness work exists and is explicitly allocated in addendum section 6. |

## Evidence limits

Executed evidence classes:

```text
STATIC_SOURCE_PROOF
UNIT_TEST
ARCHITECTURE_TEST
INTEGRATION_TEST
REAL_DATABASE
FAKE_CREDENTIAL_ZERO_OUTBOUND
PACKAGE_OR_PROVENANCE
```

Not executed and not claimed:

```text
REAL_MARKETPLACE_CREDENTIAL
SECRET_RETRIEVAL
REAL_HTTP_MARKETPLACE
REAL_PROVIDER_OR_EXTERNAL_SYSTEM
SOCKET_START_UNDER_DATABASE_AUTHORITY
PERFORMANCE_OR_LOAD
OWNER_VERIFIED_RESULT
DEPLOYMENT
```

The fake-port flow proves identity-bound request construction and refusal after
local expiry. A later real-adapter Gate must account for database authority
duration minus local monotonic round-trip and safety margin, and must refuse
REAL_HTTP until Provider/capability verification is authoritative.

## Branch convergence record

The stale local `docs/WP-P0-003-canonicalization` branch had an empty tree diff
against `main` and was removed in the prior rework. The three Dependabot
branches continue to back separate dependency-update PRs and remain isolated
from PR #16. The sole WP-P0-003 implementation line is
`feat/WP-P0-003-executable-design-validation` and its remote counterpart.

## Boundary statement

PR #16 must remain open, draft and unmerged for the independent
`CONTROLLER_WP_P0_003_IMPLEMENTATION_BACKED_DESIGN_VALIDATION_RE_REVIEW` Gate.
This package is not Design approval, merge authorization, deployment
authorization or production-write authorization. `OQ-005`, `OQ-006` and the
project-level readiness work in the addendum remain open at their allocated
Gates.

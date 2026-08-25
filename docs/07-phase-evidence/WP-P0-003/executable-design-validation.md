# WP-P0-003 executable design validation evidence

```yaml
task: CODEX_WP_P0_003_COMMIT_BEFORE_PORT_TRANSITIVE_WEB_ISOLATION_FINAL_TARGETED_REWORK_PR16
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: 392d0c9a85e7898b565168e34f915d2721dc554e
reviewed_input_tree: 4644e22d4e6162bade605030d4c5e6955c4a2631
verified_implementation_head: 7b555f5b32c96526e31f173efd221cf6d3bb99e3
verified_implementation_tree: d953a4c78029fce49ab92655fa51fb6a77c7f85f
final_package_identity: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
targeted_findings: WP3-EDV-F02-R3A, WP3-EDV-F02-R3B
preserved_findings: WP3-EDV-F02-R2A, WP3-EDV-F02-R2B, WP3-EDV-F02-R2C, WP3-EDV-F01, WP3-EDV-F02-R1A, WP3-EDV-F02-R1B, WP3-EDV-F02-R1C, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
environment: local workstation, Docker, Testcontainers, postgres:18.4
design_approved: false
targeted_rework_status: IMPLEMENTED_AWAITING_CONTROLLER
bounded_scope_quality: PRODUCTION_GRADE
project_production_complete: false
marketplace_outbound: NONE
production_write: DISABLED
```

## Package identity and manifest

| Stage | Commit | Tree | Authority |
| --- | --- | --- | --- |
| Base | `9f7688204950c64b9f6bd8629daf90a115669864` | resolved by GitHub repository | PR base |
| Controller-reviewed starting Head | `392d0c9a85e7898b565168e34f915d2721dc554e` | `4644e22d4e6162bade605030d4c5e6955c4a2631` | Controller ruling and live PR state |
| Verified implementation | `7b555f5b32c96526e31f173efd221cf6d3bb99e3` | `d953a4c78029fce49ab92655fa51fb6a77c7f85f` | local Git object and complete verification |
| Final evidence package | live PR #16 Head | live PR #16 tree | PR metadata/body after evidence-only commit |
| Tested merge | live PR #16 test merge | live merge tree and parents | GitHub API after final CI |

The starting-to-final delta is one implementation/test commit followed by one
evidence-only commit. The evidence commit cannot record its own immutable SHA;
the final Head, tree, tested-merge identity and workflow results are therefore
bound in live PR #16 metadata and body after push and CI.

## Finding-to-test matrix

| Finding | Required proof | Result |
| --- | --- | --- |
| `F02-R3A` | reject a non-auto-commit connection before SQL; close the exact ResultSet, statement and connection before executor/port; suppress port on SQL or resource-completion failure; let a second real connection observe the committed decision at port entry | PASS — `TC-CTRL-502…505` |
| `F02-R3B` | cycle-safe traversal from each `RestController` across all MarketOps dependencies; reject gateway/executor/mapper/grant/request and acquisition/object-storage ports or implementations; report the complete path; permit ordinary query paths | PASS — `TC-ARCH-030`, `F-ARCH-030/031` |
| Preserved `R2A` | 30-second server maximum, exact lease cap, immutable evidence checks, invalid/overflow requests fail with no residue | PASS — `TC-CTRL-439…442` |
| Preserved `R2B` | exact gateway query and mapper/executor ownership; local atomic one-shot consumption; real gateway semantic flow | PASS — `TC-ARCH-026…029`, `F-ARCH-026…028`, `TC-PORT-005/006`, `TC-CTRL-500/501` |
| Preserved `R2C` | cross-account predecessor refused by FK; same-account historical lineage and current-leaf selection remain valid | PASS — `TC-CTRL-443/444` |
| Preserved `R1A/R1B/R1C` | fixed database instant, temporal samples, graph completeness and exact authority allowlists | PASS — complete and focused verification |
| Preserved `F01/F03` | grant serialization, zero-residue denial and final checkpoint CAS after blocking/lease expiry | PASS — `CallAuthorityExclusivityIT` and `IngestionAuthorityAndEvidenceIT` |
| Preserved `R01/RR02` | functional migration/comment checks and distinct package identities | PASS — repository validators and this package manifest |

Independent Controller authority is still required for closure. This evidence
records implementation and test results; it does not issue a Controller verdict.

## Commit-before-port production invariant

The sole production gateway now enforces this exact order:

```text
DataSource.getConnection
→ Connection.getAutoCommit must be true
→ prepare the exact grant_call_authority statement
→ bind inputs and execute
→ require and map exactly one row
→ close ResultSet
→ close PreparedStatement
→ close Connection
→ AuthorizedAcquisitionExecutor.execute
→ AcquisitionPort.acquire
```

`spring.datasource.hikari.auto-commit: true` remains a checked-in operational
default and has both a parsed configuration assertion and a repository-contract
mutation test. The runtime `Connection.getAutoCommit()` guard is authoritative:
a false value raises
`call-authority gateway requires an independent auto-commit connection` before
statement preparation. Any `SQLException` during execution or JDBC resource
completion is converted to the gateway database-operation failure before the
executor is reached.

| Test | Observation |
| --- | --- |
| `TC-CTRL-502` | mocked `autoCommit=false`; no statement preparation and no port call; connection closed |
| `TC-CTRL-503` | real PostgreSQL connection wrapped with `autoCommit=false`; run remains `LEASED`, `last_call_seq=0`, decision evidence count 0 and port count 0 |
| `TC-CTRL-504` | mapped valid row followed by connection-close `SQLException`; ResultSet and statement close, gateway fails and port count remains 0 |
| `TC-CTRL-505` | real PostgreSQL auto-commit grant; at port callback an independently opened application-role connection observes `RUNNING`, matching run/evidence call sequence, fence token and credential identity before the invocation is recorded |

The application credential selection remains purpose-enforced by
`CredentialService`. The database credential-lineage constraint pins a
predecessor to the same Marketplace account; it does not claim to be a second
purpose constraint. No schema change was introduced for R3A or R3B.

## Transitive web isolation

The existing direct-controller rule remains active. A new independent rule
performs breadth-first traversal from every class annotated with
`@RestController`, follows dependencies whose packages are rooted at
`com.mimococo.marketops`, and uses a visited set so cycles terminate.

Traversal stops and reports a violation on any of these terminal surfaces:

- `JdbcAuthorizedAcquisitionGateway`;
- `AuthorizedAcquisitionExecutor`;
- `CallAuthorityGrantMapper`;
- `CallAuthorityGrant`;
- `AcquisitionRequest`;
- `AcquisitionPort` or any assignable implementation; and
- `ObjectStoragePort` or any assignable implementation.

The violation detail contains the complete discovered path, for example:

```text
TransitiveAcquisitionController
→ AcquisitionBridgeService
→ JdbcAuthorizedAcquisitionGateway
```

`F-ARCH-030` proves that two-hop path is rejected and that all three class names
plus path separators are present in the report. `F-ARCH-031` proves a normal
controller-to-query-service path passes. All earlier direct sensitivity
fixtures and allowlist rules remain present and passing.

## Complete backend verification

`./mvnw -B -ntp verify` completed successfully against PostgreSQL 18.4:

- 10 Flyway migrations validated and applied from an empty schema;
- interrupted-install recovery rolled back and then applied all 10 migrations;
- 213 unit and architecture tests passed;
- 152 real database and integration tests passed;
- 365 total tests passed with zero failures, errors or skips;
- `IngestionAuthorityArchitectureTest`: 23/23 passed;
- `CallAuthoritySingleUseTest`: 6/6 passed;
- `JdbcAuthorizedAcquisitionGatewayTest`: 3/3 passed;
- `AuthorizedAcquisitionFlowIT`: 3/3 passed;
- `IngestionAuthorityAndEvidenceIT`: 34/34 passed;
- `CallAuthorityExclusivityIT`: 15/15 passed; and
- all JaCoCo coverage checks passed.

The run completed in 51.429 seconds at
`2026-08-25T14:35:53+08:00`. The command summary is recorded in
`backend-verify-run.txt`.

## Focused R3 and preserved-contract verification

The focused command selected 50 unit/architecture/configuration tests and 62
real database/integration tests. All 112 passed with no failure, error or skip:

- application configuration: 18;
- architecture rules and sensitivity fixtures: 23;
- sequential/concurrent grant semantics: 6;
- JDBC gateway transaction/resource tests: 3;
- Flyway installation/recovery: 10;
- real gateway/database flows: 3;
- authority and evidence database cases: 34; and
- preserved concurrency cases: 15.

The focused run completed in 30.816 seconds at
`2026-08-25T14:36:33+08:00`. Its exact command and observations are recorded in
`f02-final-targeted-tests-run.txt`.

## Migration and repository cross-checks

- `git diff --check`: pass.
- `python3 scripts/validate_governance.py`: pass.
- combined validator unit suite: 244/244 pass.
- `TC-GLOBAL-001` Compromise Retirement Check: pass.
- `TC-GLOBAL-002` Functional JavaDoc Rewrite Check: pass.
- `TC-GLOBAL-003` Production Naming Check: pass.
- V0001–V0006 remain byte-identical to `origin/main`.
- the complete migration directory is unchanged from Controller-reviewed Head
  `392d0c9a85e7898b565168e34f915d2721dc554e`.
- V0010 SHA-256 remains
  `a3b8ca08b796c1d211f17a042a8ec546cd0009d4457e2d68dcd930dfc36a13d9`.
- no secret/PII, credential retrieval, real Marketplace outbound, Provider
  simulation, Dependabot change or production write was added.

## Controller principles audit

| # | Assessment |
| --- | --- |
| 1 | PASS — governance, active WP, ADRs/DR, Controller ruling, current Java/SQL/tests, evidence and live PR facts were cross-checked. |
| 2 | PASS FOR TARGETED SCOPE — R3A and R3B have deterministic unit, architecture and real-database proof; independent Controller acceptance remains required. |
| 3 | PASS FOR TARGETED SCOPE — the runtime connection-state guard, resource-ordering invariant, second-connection visibility proof and cycle-safe transitive graph rule are complete production controls, not a minimum slice. |
| 4 | PASS — project/WP deferred work is enumerated below and is not represented as delivered. |
| 5 | PASS — configuration is not treated as the authority, no direct-only web gap remains and no fallback/parallel authority path was added. |
| 6 | PASS — implementation follows the ruling and accepted ADRs without requiring an Owner design decision. |
| 7 | PASS — changed production JavaDoc describes current functional capability only; `TC-GLOBAL-002` passes. |
| 8 | PASS — no deprecated transaction path, web bypass or parallel legacy logic remains in the repaired boundary. |
| 9 | PASS — Compromise Retirement, Functional JavaDoc Rewrite and Production Naming checks all pass. |
| 10 | BOUNDED PRODUCTION-GRADE / PROJECT INCOMPLETE — this authority boundary is production-grade; WP-P0-003 and MarketOps are not project-level production-complete. |
| 11 | YES — project-level deferred/readiness work remains explicitly allocated and is not hidden by this rework. |

Project-level deferred/readiness scope remains: scheduler/worker lifecycle,
retry/rate-limit/circuit/backpressure/replay/reconciliation/backfill engines;
approved secret manager and object storage; real Marketplace adapters and
Provider semantics; webhook/manual/file-upload authorization; controlled file
import; downstream idempotent effects; operator recovery surfaces; Command
Outbox; and deployment, retention, backup, personal-data and cross-border legal
readiness.

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

`CI_EXECUTION` is bound only after the final package is pushed and the exact
GitHub workflow/job set completes.

Not executed and not claimed:

```text
REAL_MARKETPLACE_CREDENTIAL: NONE
SECRET_RETRIEVAL: NONE
REAL_HTTP_MARKETPLACE: NOT_RUN
REAL_PROVIDER_OR_EXTERNAL_SYSTEM: NOT_RUN
SOCKET_START_UNDER_DATABASE_AUTHORITY: NOT_RUN
PERFORMANCE_OR_LOAD: NOT_RUN
OWNER_VERIFIED_RESULT: NOT_RUN
DEPLOYMENT: NOT_RUN
```

The fake-port flow proves committed database decision visibility before local
port entry, identity-bound request construction and local one-shot consumption.
It does not claim remote exactly-once, unknown-commit closure or Provider
idempotency.

## Branch and boundary statement

The three Dependabot lines remain isolated in their own dependency-update PRs.
The sole WP-P0-003 implementation line is
`feat/WP-P0-003-executable-design-validation` and its remote counterpart.

PR #16 must remain open, draft and unmerged for
`CONTROLLER_WP_P0_003_IMPLEMENTATION_BACKED_DESIGN_VALIDATION_RE_REVIEW`.
This package is not Design approval, merge authorization, deployment
authorization or production-write authorization. `OQ-005`, `OQ-006` and the
project-level readiness work remain open at their allocated Gates.

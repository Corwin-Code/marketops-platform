# WP-P0-003 executable design validation evidence

```yaml
task: CODEX_WP_P0_003_POLYMORPHIC_REACHABILITY_PORT_ASSIGNABILITY_VISIBILITY_TARGETED_REWORK_PR16
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: de34774af5f7c8f10dd39be40149da1a2aa3e5b7
reviewed_input_tree: 06dd651ed3198bdf1f7d95348dcbf69e8e04bda3
verified_implementation_head: 52ff670c2bf8f44a1709273ad60036d4610d3f3c
verified_implementation_tree: a5d99f2bf0a4dd583dc0a32b9d166fe656d4d9d2
final_package_identity: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
targeted_findings: WP3-EDV-F02-R4A, WP3-EDV-F02-R4B, WP3-EDV-F02-R4C
preserved_findings: WP3-EDV-F02-R3A, WP3-EDV-F02-R3B-CONCRETE-STATIC, WP3-EDV-F02-R2A, WP3-EDV-F02-R2B, WP3-EDV-F02-R2C, WP3-EDV-F01, WP3-EDV-F02-R1A, WP3-EDV-F02-R1B, WP3-EDV-F02-R1C, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
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
| Controller-reviewed starting Head | `de34774af5f7c8f10dd39be40149da1a2aa3e5b7` | `06dd651ed3198bdf1f7d95348dcbf69e8e04bda3` | Controller ruling and live PR state |
| Verified implementation | `52ff670c2bf8f44a1709273ad60036d4610d3f3c` | `a5d99f2bf0a4dd583dc0a32b9d166fe656d4d9d2` | local Git object and complete verification |
| Final evidence package | live PR #16 Head | live PR #16 tree | PR metadata/body after evidence-only commit |
| Tested merge | live PR #16 test merge | live merge tree and parents | GitHub API after final CI |

The starting-to-final delta is one implementation/test commit followed by one
evidence-only commit. The evidence commit cannot record its own immutable SHA;
the final Head, tree, tested-merge identity and workflow results are therefore
bound in live PR #16 metadata and body after push and CI.

## Finding-to-test matrix

| Finding | Required proof | Result |
| --- | --- | --- |
| `F02-R4A` | traverse the complete production universe from direct and meta-annotated controllers using direct dependencies plus interface/abstract-to-concrete runtime dispatch; reject every unsafe selectable implementation with the complete path and `=>` provenance; remain cycle-safe and permit safe-only dispatch | PASS — `TC-ARCH-030`, `F-ARCH-030…035`, `F-ARCH-040` |
| `F02-R4B` | evaluate port ownership by assignability for interfaces, abstract types and concrete classes; reject external subinterfaces, direct implementations and inherited implementations for both Acquisition and ObjectStorage ports; permit owning-module equivalents | PASS — `F-ARCH-020`, `F-ARCH-036…038`, `F-ARCH-038C` |
| `F02-R4C` | require exact package-private grant class and constructors; retain mapper-only construction and executor-only consumption; reject a same-JDBC-package non-mapper constructor without public production visibility | PASS — `TC-ARCH-039`, `F-ARCH-023I` |
| Preserved `R3A/R3B concrete-static` | reject caller-owned transactions and complete JDBC resources before port entry; preserve the concrete dependency graph and full-path reporting | PASS — `TC-CTRL-502…505`, `F-ARCH-030/031` |
| Preserved `R2A` | 30-second server maximum, exact lease cap, immutable evidence checks, invalid/overflow requests fail with no residue | PASS — `TC-CTRL-439…442` |
| Preserved `R2B` | exact gateway query and mapper/executor ownership; local atomic one-shot consumption; real gateway semantic flow | PASS — `TC-ARCH-026…029`, `F-ARCH-026…028`, `TC-PORT-005/006`, `TC-CTRL-500/501` |
| Preserved `R2C` | cross-account predecessor refused by FK; same-account historical lineage and current-leaf selection remain valid | PASS — `TC-CTRL-443/444` |
| Preserved `R1A/R1B/R1C` | fixed database instant, temporal samples, graph completeness and exact authority allowlists | PASS — complete and focused verification |
| Preserved `F01/F03` | grant serialization, zero-residue denial and final checkpoint CAS after blocking/lease expiry | PASS — `CallAuthorityExclusivityIT` and `IngestionAuthorityAndEvidenceIT` |
| Preserved `R01/RR02` | functional migration/comment checks and distinct package identities | PASS — repository validators and this package manifest |

Independent Controller authority is still required for closure. This evidence
records implementation and test results; it does not issue a Controller verdict.

## Preserved commit-before-port production invariant

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
purpose constraint. No schema change was introduced for R4A, R4B or R4C.

## Polymorphic web isolation, port assignability and grant visibility

The existing direct-controller rule and concrete-static graph remain active.
The strengthened rule receives the complete production import, starts at every
direct or meta-annotated `RestController`, and performs breadth-first traversal
over direct bytecode dependencies plus runtime dispatch edges from any interface
or abstract contract to every concrete assignable MarketOps class. Direct edges
are reported as `->`; dispatch edges are reported as `=>`. Targets are sorted
and visited class names are bounded, so reports are deterministic and cycles
terminate.

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
PolymorphicAcquisitionController
-> AcquisitionUseCase
=> GatewayBackedAcquisitionService
-> JdbcAuthorizedAcquisitionGateway
```

`F-ARCH-032` proves unsafe interface dispatch is rejected with all four types
and both markers. `F-ARCH-033` proves a safe implementation cannot mask an
unsafe selectable implementation. `F-ARCH-034` permits safe-only dispatch;
`F-ARCH-035` rejects a meta-annotated controller reaching an unsafe concrete
subclass through an abstract contract; `F-ARCH-040` proves a cyclic safe graph
terminates. `F-ARCH-030/031` retain the concrete two-hop rejection and ordinary
query-path pass.

The ownership rule now applies `isAssignableTo(AcquisitionPort)` or
`isAssignableTo(ObjectStoragePort)` to every imported type. `F-ARCH-036…038`
reject external subinterfaces, their implementations and subclasses inheriting
owning implementations for both port families. `F-ARCH-038C` proves the same
type shapes are valid inside the owning module. The direct-call rule remains
unchanged and independently active.

`CallAuthorityGrant` and every constructor have exact package-private
visibility under `TC-ARCH-039`. Outside-package fixtures that depended on a
public grant were removed. `F-ARCH-023I` instead compiles in the exact JDBC
package and proves a non-mapper constructor call is rejected. Production still
has one constructor caller (`CallAuthorityGrantMapper`) and one consumer
(`AuthorizedAcquisitionExecutor`); no reflection, public factory or alternate
grant path was added.

## Complete backend verification

`./mvnw -B -ntp verify` completed successfully against PostgreSQL 18.4:

- 10 Flyway migrations validated and applied from an empty schema;
- interrupted-install recovery rolled back and then applied all 10 migrations;
- 222 unit and architecture tests passed;
- 152 real database and integration tests passed;
- 374 total tests passed with zero failures, errors or skips;
- `IngestionAuthorityArchitectureTest`: 32/32 passed;
- `CallAuthoritySingleUseTest`: 6/6 passed;
- `JdbcAuthorizedAcquisitionGatewayTest`: 3/3 passed;
- `AuthorizedAcquisitionFlowIT`: 3/3 passed;
- `IngestionAuthorityAndEvidenceIT`: 34/34 passed;
- `CallAuthorityExclusivityIT`: 15/15 passed; and
- all JaCoCo coverage checks passed.

The run completed in 1 minute at
`2026-08-25T15:40:58+08:00`. The command summary is recorded in
`backend-verify-run.txt`.

## Focused R4 and preserved-contract verification

The focused command selected 59 unit/architecture/configuration tests and 62
real database/integration tests. All 121 passed with no failure, error or skip:

- application configuration: 18;
- architecture rules and sensitivity fixtures: 32;
- sequential/concurrent grant semantics: 6;
- JDBC gateway transaction/resource tests: 3;
- Flyway installation/recovery: 10;
- real gateway/database flows: 3;
- authority and evidence database cases: 34; and
- preserved concurrency cases: 15.

The focused run completed in 32.206 seconds at
`2026-08-25T15:42:03+08:00`. Its exact command and observations are recorded in
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
  `de34774af5f7c8f10dd39be40149da1a2aa3e5b7`.
- V0010 SHA-256 remains
  `a3b8ca08b796c1d211f17a042a8ec546cd0009d4457e2d68dcd930dfc36a13d9`.
- no secret/PII, credential retrieval, real Marketplace outbound, Provider
  simulation, Dependabot change or production write was added.

## Controller principles audit

| # | Assessment |
| --- | --- |
| 1 | PASS — governance, active WP, ADRs/DR, Controller ruling, current Java/SQL/tests, evidence and live PR facts were cross-checked. |
| 2 | PASS FOR TARGETED SCOPE — R4A/R4B/R4C have deterministic architecture sensitivity proof and all preserved runtime/database suites pass; independent Controller acceptance remains required. |
| 3 | PASS FOR TARGETED SCOPE — the complete-universe dispatch graph, assignability-based port rule and package-private grant boundary are production controls, not a minimum vertical slice. |
| 4 | PASS — project/WP deferred work is enumerated below and is not represented as delivered. |
| 5 | PASS — no concrete-only reachability gap, raw-interface-only ownership gap, public-grant compromise, fallback or parallel authority path remains. |
| 6 | PASS — implementation follows the ruling and accepted ADRs without requiring an Owner design decision. |
| 7 | PASS — changed production JavaDoc describes current functional capability only; architecture-fixture comments describe fixture purpose without historical phase semantics; `TC-GLOBAL-002` passes. |
| 8 | PASS — public-visibility-dependent fixtures were removed after evaluation; no deprecated transaction path, web bypass or parallel legacy logic remains in the repaired boundary. |
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

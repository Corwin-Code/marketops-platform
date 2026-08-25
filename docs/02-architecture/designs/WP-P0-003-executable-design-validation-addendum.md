# WP-P0-003 — Executable Design Validation Addendum

```yaml
document_type: as_built_design_addendum
task: CODEX_WP_P0_003_COMMIT_BEFORE_PORT_TRANSITIVE_WEB_ISOLATION_FINAL_TARGETED_REWORK_PR16
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
work_package: WP-P0-003
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: 392d0c9a85e7898b565168e34f915d2721dc554e
reviewed_input_tree: 4644e22d4e6162bade605030d4c5e6955c4a2631
verified_implementation_head: 7b555f5b32c96526e31f173efd221cf6d3bb99e3
verified_implementation_tree: d953a4c78029fce49ab92655fa51fb6a77c7f85f
final_package_identity: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
targeted_findings: WP3-EDV-F02-R3A, WP3-EDV-F02-R3B
preserved_findings: WP3-EDV-F02-R2A, WP3-EDV-F02-R2B, WP3-EDV-F02-R2C, WP3-EDV-F01, WP3-EDV-F02-R1A, WP3-EDV-F02-R1B, WP3-EDV-F02-R1C, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
design_approved: false
targeted_rework_status: IMPLEMENTED_AWAITING_CONTROLLER
bounded_scope_quality: PRODUCTION_GRADE
project_production_complete: false
marketplace_outbound: NONE
secret_retrieval: NONE
production_write: DISABLED
next_gate: CONTROLLER_WP_P0_003_IMPLEMENTATION_BACKED_DESIGN_VALIDATION_RE_REVIEW
```

The implementation identity binds the production code and tests that passed
complete backend verification. An evidence-only commit cannot contain its own
Git identity, so live PR #16 metadata and body are authoritative for the final
package Head, tree, tested-merge identity and CI result.

## 1. Targeted finding implementation

| Finding | As-built correction | Executable evidence | Rework state |
| --- | --- | --- | --- |
| `WP3-EDV-F02-R3A` | `JdbcAuthorizedAcquisitionGateway` checks the real connection's `autoCommit` state before preparing SQL and rejects caller-owned transactions. It maps one database decision, closes ResultSet, statement and connection, and only then invokes the executor/port. SQL or resource-completion failure prevents port entry. The checked-in Hikari default is guarded but is not treated as the runtime authority. | `TC-CTRL-502…505`, configuration source assertion and repository-contract mutation test | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-F02-R3B` | An independent cycle-safe architecture rule traverses every `RestController` dependency graph across MarketOps classes and rejects any path to the gateway, executor, mapper, grant, request, acquisition port/implementation or object-storage port/implementation. Violations contain the full path; ordinary query paths remain valid. | `TC-ARCH-030`, `F-ARCH-030/031` | `IMPLEMENTED_AWAITING_CONTROLLER` |

R2A, the exact-query/local-one-shot portion of R2B, R2C, R1A/R1B/R1C,
`WP3-EDV-F01`, `WP3-EDV-F03`, `WP3-EDV-R01` and `WP3-EDV-RR02` remain
executable and passing. This rework does not weaken or redefine them. Closure
authority for R3A/R3B remains with the next independent Controller Gate.

## 2. Commit-before-port invariant

The production chain is now:

```text
JdbcAuthorizedAcquisitionGateway
  → DataSource.getConnection
  → require Connection.getAutoCommit() == true
  → prepare and bind the exact grant_call_authority query
  → execute and require exactly one row
  → CallAuthorityGrantMapper
  → close ResultSet
  → close PreparedStatement
  → close Connection
  → AuthorizedAcquisitionExecutor
  → atomically consume the internal grant
  → AcquisitionRequest.fromDatabaseAuthority
  → AcquisitionPort.acquire
  → AcquisitionResult
```

The authoritative guard is the connection state obtained at runtime. If it is
false, the gateway raises
`call-authority gateway requires an independent auto-commit connection` before
statement preparation. The base configuration retains:

```yaml
spring:
  datasource:
    hikari:
      auto-commit: true
```

`ApplicationConfigurationTest` parses and asserts that value. The production
readiness validator also requires it, and a mutation test proves changing it to
false is rejected. These controls prevent configuration drift; they do not
replace the runtime guard.

Because executor invocation is outside all three JDBC try-with-resources
scopes, an `SQLException` from statement execution, ResultSet closure,
statement closure or connection completion follows the database failure path
and cannot reach the port.

The real-database visibility proof invokes a fake local port only after the
gateway has completed its JDBC scope. Inside that callback it opens a second
application-role connection and observes:

```text
run.state = RUNNING
run.last_call_seq = request.call_seq
decision.call_seq = request.call_seq
decision.fence_token = request.fence_token
decision.credential_id = request.credential_id
```

The observation occurs before the port callback records its invocation. This
proves commit-before-port for the local doorway without claiming remote
exactly-once behavior.

## 3. Transitive request-thread isolation

The existing direct-dependency rule remains active. The independent transitive
rule starts at every `@RestController`, follows dependencies rooted at
`com.mimococo.marketops`, and tracks visited class names. Breadth-first traversal
therefore terminates even when application dependencies contain cycles.

The terminal forbidden set is:

```text
JdbcAuthorizedAcquisitionGateway
AuthorizedAcquisitionExecutor
CallAuthorityGrantMapper
CallAuthorityGrant
AcquisitionRequest
AcquisitionPort and assignable implementations
ObjectStoragePort and assignable implementations
```

Every violation reports the discovered controller-to-terminal class sequence
joined with ` -> `. The two-hop mutation fixture proves the report includes the
controller, bridge service and gateway. A conforming query controller and query
service prove the graph rule does not prohibit ordinary request-facing reads.
The complete earlier direct rules and their mutation fixtures remain present.

## 4. Preserved production contracts

- The exact production grant query remains owned only by
  `JdbcAuthorizedAcquisitionGateway`.
- Mapper, grant and executor remain package-private collaborators; architecture
  allowlists still reject synthetic ResultSet mapping, alternate executor
  callers, alternate request factories and direct port calls.
- `CallAuthorityGrant` remains atomic one-shot state. Sequential and concurrent
  reuse reach the fake port only once.
- R2A keeps the 30-second server cap, locked lease/control-boundary minimum and
  immutable decision evidence.
- R2C keeps account-pinned credential predecessor identity. Same-purpose
  credential selection is enforced by `CredentialService`; the database FK
  itself claims only same-account lineage.
- V0001–V0006 remain byte-identical to `origin/main`. The migration directory,
  including V0010 SHA-256
  `a3b8ca08b796c1d211f17a042a8ec546cd0009d4457e2d68dcd930dfc36a13d9`,
  is unchanged from Controller-reviewed Head `392d0c9`.
- F01 serialization, F03 final-checkpoint CAS, ACL denial, zero-residue failure,
  fixed database time and temporal/graph completeness remain passing.
- No network client, Provider simulation, credential retrieval, secret
  material, production write or real Marketplace call was added.

## 5. Verification record and evidence limit

The complete backend command passed 213 unit/architecture tests and 152
real-database/integration tests: 365 total, zero failures/errors/skips, with all
JaCoCo checks met. The focused command passed 50 unit/architecture/configuration
tests and 62 real-database/integration tests: 112 total. Governance validation,
production-readiness validation and the 244-test validator suite also passed.

Executed evidence proves runtime transaction exclusion, resource ordering,
committed decision visibility, local identity-bound port entry, local one-shot
consumption and transitive request-thread isolation. It does not prove socket
start under database authority, Provider behavior, credential retrieval,
performance, deployment or end-to-end worker recovery.

No real Marketplace credential, real outbound HTTP, Provider system or
production write was used. Independent Controller review and GitHub CI on the
final evidence package remain separate Gates.

## 6. Production-readiness classification

This package is production-grade for the bounded executable authority scope.
Runtime correctness does not depend only on configuration, the port is not
entered while the grant transaction/resources remain open, and request-facing
code cannot reach authority surfaces indirectly through another MarketOps
class. No parallel older authority path or fallback behavior was introduced.

It is not the complete WP-P0-003 runtime or a project-level
production-complete MarketOps product. Project-level readiness remains gated by
explicitly allocated work:

- scheduler/worker lifecycle, retry, rate limiting, circuit state,
  backpressure, replay, reconciliation and backfill engines;
- approved Secret Manager and S3-compatible object storage (`OQ-006`);
- real Ozon/Wildberries adapters, verified capabilities, quotas, idempotency and
  native status semantics (WP-P0-005/WP-P0-006);
- public webhook/manual/file-upload authentication and authorization (`OQ-005`);
- controlled file import (WP-P0-003B);
- downstream idempotent domain and ledger effects (WP-P0-005…WP-P0-007);
- operator product views and authenticated recovery surfaces (WP-P0-008);
- platform-write Command Outbox (`INT-017`); and
- deployment, hosting, retention, backup, personal-data and cross-border legal
  readiness (`OQ-107`).

These are declared work-package/project allocations, not hidden omissions or
compromise implementations inside this bounded Controller rework.

# WP-P0-003 — Executable Design Validation Addendum

```yaml
document_type: as_built_design_addendum
task: CODEX_WP_P0_003_POLYMORPHIC_REACHABILITY_PORT_ASSIGNABILITY_VISIBILITY_TARGETED_REWORK_PR16
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
work_package: WP-P0-003
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: de34774af5f7c8f10dd39be40149da1a2aa3e5b7
reviewed_input_tree: 06dd651ed3198bdf1f7d95348dcbf69e8e04bda3
verified_implementation_head: 52ff670c2bf8f44a1709273ad60036d4610d3f3c
verified_implementation_tree: a5d99f2bf0a4dd583dc0a32b9d166fe656d4d9d2
final_package_identity: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
targeted_findings: WP3-EDV-F02-R4A, WP3-EDV-F02-R4B, WP3-EDV-F02-R4C
preserved_findings: WP3-EDV-F02-R3A, WP3-EDV-F02-R3B-CONCRETE-STATIC, WP3-EDV-F02-R2A, WP3-EDV-F02-R2B, WP3-EDV-F02-R2C, WP3-EDV-F01, WP3-EDV-F02-R1A, WP3-EDV-F02-R1B, WP3-EDV-F02-R1C, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
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
| `WP3-EDV-F02-R4A` | The cycle-safe controller graph now combines direct bytecode dependencies with runtime dispatch edges from every interface or abstract contract to every concrete assignable MarketOps implementation in the complete production import. Direct and meta-annotated `RestController` roots are covered; dispatch evidence uses `=>`, and every violation retains the complete root-to-forbidden path. | `TC-ARCH-030`, `F-ARCH-030…035`, `F-ARCH-040` | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-F02-R4B` | Port ownership is evaluated with `isAssignableTo(AcquisitionPort)` or `isAssignableTo(ObjectStoragePort)` for interfaces, abstract types and concrete classes. External subinterfaces, direct implementations and inherited implementations are rejected; equivalent owning-module types pass. | `F-ARCH-020`, `F-ARCH-036…038`, `F-ARCH-038C` | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-F02-R4C` | `CallAuthorityGrant` and its constructor are exactly package-private. Obsolete outside-package fixtures that required public production visibility were removed, while an exact JDBC-package non-mapper constructor fixture proves the mapper-only ownership rule remains sensitive. | `TC-ARCH-039`, `F-ARCH-023I` | `IMPLEMENTED_AWAITING_CONTROLLER` |

R3A, the concrete-static portion of R3B, R2A, the exact-query/local-one-shot
portion of R2B, R2C, R1A/R1B/R1C, `WP3-EDV-F01`, `WP3-EDV-F03`,
`WP3-EDV-R01` and `WP3-EDV-RR02` remain executable and passing. This rework
does not weaken or redefine them. Closure authority for R4A/R4B/R4C remains
with the next independent Controller Gate.

## 2. Preserved commit-before-port invariant

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

## 3. Polymorphic request-thread isolation and port ownership

The existing direct-dependency rule and its concrete-static graph remain
active. The strengthened transitive rule receives the complete imported
production `JavaClasses`, starts at every direct or meta-annotated
`RestController`, and follows two deterministic edge kinds:

```text
source -> target   direct bytecode dependency
contract => type  runtime-selectable concrete assignable implementation
```

Runtime dispatch expansion applies to every interface and abstract class, is
resolved by ArchUnit assignability rather than package-name inference, and is
sorted for deterministic reports. A visited type-name set makes the union graph
cycle-safe. Because every concrete assignable implementation is expanded, a
safe implementation cannot hide an unsafe selectable implementation.

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
with `->` and `=>` edge provenance. Fixtures prove unsafe interface dispatch,
mixed safe/unsafe implementations and meta-annotated abstract dispatch are
rejected; safe-only dispatch and cyclic non-authority graphs pass. The earlier
concrete two-hop failure and normal query-path success remain covered. No HTTP
route or production application bean was introduced by these architecture-only
fixtures.

The port ownership rule separately applies `isAssignableTo` to every imported
type. It rejects external Acquisition and ObjectStorage subinterfaces, their
implementations and subclasses that inherit an owning implementation. Matching
subinterfaces and implementations inside the owning module remain valid.

`CallAuthorityGrant` now has default class and constructor visibility. The
gateway remains result-only, the mapper remains the only production constructor
caller, and the executor remains the only grant consumer. An exact JDBC-package
non-mapper fixture remains compilable and is rejected by the constructor
allowlist without reopening public production visibility.

## 4. Preserved production contracts

- The exact production grant query remains owned only by
  `JdbcAuthorizedAcquisitionGateway`.
- Mapper, grant, grant constructor and executor remain package-private
  collaborators; architecture allowlists still reject synthetic ResultSet
  mapping, alternate executor callers, alternate request factories and direct
  port calls.
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
  is unchanged from Controller-reviewed Head `de34774`.
- F01 serialization, F03 final-checkpoint CAS, ACL denial, zero-residue failure,
  fixed database time and temporal/graph completeness remain passing.
- No network client, Provider simulation, credential retrieval, secret
  material, production write or real Marketplace call was added.

## 5. Verification record and evidence limit

The complete backend command passed 222 unit/architecture tests and 152
real-database/integration tests: 374 total, zero failures/errors/skips, with all
JaCoCo checks met. The focused command passed 59 unit/architecture/configuration
tests and 62 real-database/integration tests: 121 total. Governance validation,
production-readiness validation and the 244-test validator suite also passed.

Executed evidence proves runtime transaction exclusion, resource ordering,
committed decision visibility, local identity-bound port entry, local one-shot
consumption, polymorphic request-thread isolation, assignability-based port
ownership and exact grant visibility. It does not prove socket
start under database authority, Provider behavior, credential retrieval,
performance, deployment or end-to-end worker recovery.

No real Marketplace credential, real outbound HTTP, Provider system or
production write was used. Independent Controller review and GitHub CI on the
final evidence package remain separate Gates.

## 6. Production-readiness classification

This package is production-grade for the bounded executable authority scope.
Runtime correctness does not depend only on configuration, the port is not
entered while the grant transaction/resources remain open, request-facing code
cannot reach authority surfaces through direct, interface or abstract dispatch,
and port ownership cannot be escaped through subtyping or inheritance. No
parallel older authority path or fallback behavior was introduced.

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

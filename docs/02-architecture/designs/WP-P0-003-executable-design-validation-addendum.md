# WP-P0-003 — Executable Design Validation Addendum

```yaml
document_type: as_built_design_addendum
task: CODEX_WP_P0_003_CALL_AUTHORITY_ENVELOPE_PROVENANCE_FINAL_TARGETED_REWORK_PR16
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
work_package: WP-P0-003
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: 85e901e6d7086b9fb1620d7a7d2c1257f9d0a25c
reviewed_input_tree: 70429664de61e86a305d7923da23e9e303839d86
verified_implementation_head: fc47e018d86902566a1219a6c4cbf84429c4d035
verified_implementation_tree: e2e5888cf4783bd97e2ce0388bd9b46c77a9fc92
final_package_identity: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
targeted_findings: WP3-EDV-F02-R2A, WP3-EDV-F02-R2B, WP3-EDV-F02-R2C
preserved_closed_findings: WP3-EDV-F01, WP3-EDV-F02-R1A, WP3-EDV-F02-R1B, WP3-EDV-F02-R1C, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
design_approved: false
targeted_rework_status: IMPLEMENTED_AWAITING_CONTROLLER
bounded_scope_quality: PRODUCTION_GRADE
project_production_complete: false
marketplace_outbound: NONE
secret_retrieval: NONE
production_write: DISABLED
next_gate: CONTROLLER_WP_P0_003_IMPLEMENTATION_BACKED_DESIGN_VALIDATION_RE_REVIEW
```

The implementation identity binds the production code, draft migration and tests
that passed the complete backend verification. An evidence-only commit cannot
contain its own Git identity, so live PR #16 metadata and body are authoritative
for the final package Head, tree, tested-merge identity and CI result.

## 1. Targeted finding implementation

| Finding | As-built correction | Executable evidence | Rework state |
| --- | --- | --- | --- |
| `WP3-EDV-F02-R2A` | `grant_call_authority` treats the caller interval only as a request and applies a server-owned 30-second maximum. Authority is the minimum of requested deadline, server deadline, locked run lease and locked control snapshot. The grant and immutable decision evidence record both lease and server-policy deadlines; database checks prohibit authority beyond either. Invalid or overflowing requests fail closed as `MO016` before sequence/evidence residue. | `TC-CTRL-439…442` plus migration checks | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-F02-R2B` | `JdbcAuthorizedAcquisitionGateway` is the sole owner of the exact stored-function query, exact-one-row mapping, JDBC resource closure and immediate executor invocation. Mapper, grant and executor are internal collaborators; the grant uses atomic single consumption and cannot cross the port API. Exact ArchUnit allowlists reject synthetic result mapping, controller access and second executor callers. | `TC-ARCH-026…029`, `F-ARCH-026…028`, `TC-PORT-005/006`, `TC-CTRL-500/501` | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-F02-R2C` | V0010 replaces the single-column credential predecessor FK with an account-pinned composite FK. Same-account active or historical lineage remains legal; cross-account lineage is unrepresentable. | `TC-CTRL-443/444` | `IMPLEMENTED_AWAITING_CONTROLLER` |

The prior Controller closure of `WP3-EDV-F01`, R1A/R1B/R1C, `WP3-EDV-F03`,
`WP3-EDV-R01` and `WP3-EDV-RR02` is preserved. This rework does not reopen,
weaken or redefine those findings. Closure authority for R2A/R2B/R2C remains
with the next independent Controller Gate.

## 2. Lease-bound, server-capped authority

The database grant transaction uses this ordering:

```text
run FOR UPDATE
→ exact Job FOR SHARE
→ four ordered control-epoch locks
→ evaluated := database clock
→ subject/scope/Credential/control-boundary evaluation at evaluated
→ grant_at := database clock
→ requested_deadline := grant_at + p_requested_authority
→ server_policy_deadline := grant_at + interval '30 seconds'
→ authority expiry = min(requested_deadline,
                         server_policy_deadline,
                         locked run lease deadline,
                         control snapshot valid_until)
→ guarded call-sequence transition against the same fence, owner and lease deadline
→ immutable decision evidence
→ structured identity-bound grant
```

The server maximum is exactly `interval '30 seconds'`. `NULL`, zero, negative
or timestamp-overflowing requests raise `MO016`. A caller can shorten an
authority but cannot extend server policy, the consumed lease or the control
snapshot. Java validates the returned envelope again, but correctness does not
depend on Java behavior.

The structured grant and `ops.authorization_decision_evidence` carry:

```text
run_lease_expires_at
server_policy_deadline
```

Database constraints require expiry after grant, lease and server deadline after
grant, and:

```text
call_authority_expires_at <= run_lease_expires_at
call_authority_expires_at <= server_policy_deadline
```

## 3. Exact database provenance and one-shot execution

The production chain is:

```text
JdbcAuthorizedAcquisitionGateway
  → SELECT * FROM platform.grant_call_authority(?, ?, ?, ?, CAST(? AS interval), ?)
  → require exactly one ResultSet row
  → CallAuthorityGrantMapper
  → internal CallAuthorityGrant
  → AuthorizedAcquisitionExecutor
  → AtomicBoolean.compareAndSet(false, true)
  → expiry check
  → AcquisitionRequest copied from the complete consumed envelope
  → AcquisitionPort.acquire
  → AcquisitionResult
```

The gateway owns `DataSource`, `Connection`, `PreparedStatement` and `ResultSet`
lifetimes. The mapper and executor are package-private. The grant is not exposed
or accepted by a module API, and its consumption operation is package-private.
The public port request has a private constructor; exact architecture rules
confine its functional factory to the sole executor.

The exact production allowlist is:

| Protected operation | Sole allowed caller |
| --- | --- |
| map the grant `ResultSet` | `JdbcAuthorizedAcquisitionGateway` |
| invoke `AuthorizedAcquisitionExecutor.execute` | `JdbcAuthorizedAcquisitionGateway` |
| construct `CallAuthorityGrant` | `CallAuthorityGrantMapper` |
| invoke `AcquisitionRequest.fromDatabaseAuthority` | `AuthorizedAcquisitionExecutor` |
| invoke `AcquisitionPort.acquire` or a concrete implementation | `AuthorizedAcquisitionExecutor` |

No `RestController` may depend on the gateway, executor, mapper, grant, request
or acquisition port. Mutation fixtures cover a shape-valid synthetic
`ResultSet`, controller-via-executor, a second internal executor caller, direct
port calls and grant/request construction. Sequential and concurrent reuse of
the same grant are refused before a second port call.

This is local one-shot consumption of one mapped database decision. It does not
claim durable remote exactly-once behavior, unknown-commit closure or Provider
idempotency; those belong to the later real-worker/Adapter Gate.

## 4. Account-pinned credential rotation

Credential predecessor identity is constrained by:

```text
FOREIGN KEY (replaces_credential_id, marketplace_account_id)
REFERENCES platform.credential_metadata (id, marketplace_account_id)
```

V0006 is unchanged. V0010 replaces the prior self-reference while the PR is
still an unmerged draft migration sequence. A credential in Account A cannot
name a predecessor in Account B. A same-account historical predecessor remains
valid, and the R1B leaf/cycle/reachability resolver continues to select the
current effective leaf.

## 5. Preserved contracts and evidence limit

- V0001–V0006 remain byte-identical to `origin/main`; V0007–V0010 remain the
  draft forward-only sequence in PR #16.
- The R1A single database instant and two 201-point boundary samples, R1B graph
  completeness, R1C exact direct-call/constructor allowlists, F01 serialization,
  F03 final-checkpoint CAS, endpoint pinning, ACL denial and zero-residue refusal
  remain executable and passing.
- No network client, Provider simulation, credential retrieval, secret material,
  production write or real Marketplace call is added.
- Changed production comments and JavaDoc describe only current functional
  capability. Superseded public grant/executor types and manual test-only grant
  assembly are removed rather than retained in parallel.

Executed evidence proves a bounded database-authority and fake-port flow. It
does not prove socket start under database authority, Provider behavior,
credential retrieval, performance, deployment or end-to-end worker recovery.

## 6. Production-readiness classification

This package is production-grade for the bounded executable authority scope:
there is no known caller-widened lease window, forged provenance seam,
replayable in-process grant, cross-account credential lineage, parallel legacy
authority path, stale changed-source comment or scaffold identifier in the
repaired boundary. It is not the complete WP-P0-003 runtime or a project-level
production-complete MarketOps product.

Project-level readiness remains gated by explicitly allocated work:

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

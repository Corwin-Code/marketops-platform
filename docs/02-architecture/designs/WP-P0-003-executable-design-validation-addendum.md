# WP-P0-003 — Executable Design Validation Addendum

```yaml
document_type: as_built_design_addendum
task: WP_P0_003_EXECUTABLE_DESIGN_VALIDATION_F02_FINAL_TARGETED_REWORK
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
work_package: WP-P0-003
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: 937245514881dec580d3f4f6651e94da44536dde
reviewed_input_tree: fd2e6b0bb3330a9e02a16f440aa424de0ca7c1d2
verified_implementation_head: d93b931c7d2d278ed494ea632cada7f165144b0b
verified_implementation_tree: 3af7ef725420b5373556676a7a46cd52ffec9470
package_identity_authority: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
targeted_findings: WP3-EDV-F02-R1A, WP3-EDV-F02-R1B, WP3-EDV-F02-R1C
preserved_closed_findings: WP3-EDV-F01, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
design_approved: false
targeted_rework_status: IMPLEMENTED_AWAITING_CONTROLLER
bounded_scope_quality: PRODUCTION_GRADE
project_production_complete: false
marketplace_outbound: NONE
secret_retrieval: NONE
production_write: DISABLED
next_gate: CONTROLLER_WP_P0_003_IMPLEMENTATION_BACKED_DESIGN_VALIDATION_RE_REVIEW
```

The implementation identity binds the code, migration and tests that passed the
complete backend verification. The final evidence-only commit cannot embed its
own Git identity; live PR #16 metadata and body are therefore authoritative for
the final package Head, tree and tested-merge identity.

## 1. Targeted finding closure

| Finding | As-built correction | Executable evidence | Rework state |
| --- | --- | --- | --- |
| `WP3-EDV-F02-R1A` | `grant_call_authority` captures `evaluated := clock_timestamp()` immediately after exactly four epoch locks. Subject expiry, selected scope-grant window, Credential window and `control_snapshot_temporal` all consume this one value. Only `grant_at` is read later for issuance; the final lease predicate also reuses `grant_at`. The non-public production evaluator accepts an explicit database instant but cannot issue authority and is not executable by `marketops_app`. | `TC-CTRL-434/435`; 201-point Credential and scope mutation samples; helper ACL assertion | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-F02-R1B` | The Credential resolver uses `UNION ALL`, a visited UUID path and an explicit repeated-node flag. Acceptance requires candidates present, exactly one leaf, zero cycle rows and `reachable_count = candidate_count`. Attached-leaf cycles, disconnected cycles, multiple leaves, empty/disabled sets and `STORE_SET` all fail closed. | `TC-CTRL-430/432/436/437/438` | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-F02-R1C` | Exact bytecode-call allowlists replace module-wide ownership: only `AuthorizedAcquisitionExecutor` may invoke an `AcquisitionPort.acquire` implementation or call `AcquisitionRequest.from`; only `CallAuthorityGrantMapper` may invoke the grant constructor. The mapper reads every value from the structured JDBC result. Inside-owner and outside-owner mutation fixtures prove each bypass is rejected. | `TC-ARCH-021/023/024`; `F-ARCH-021/021I/023/023I/024/025`; `TC-CTRL-500/501` | `IMPLEMENTED_AWAITING_CONTROLLER` |

The prior Controller closed `WP3-EDV-F01`, `WP3-EDV-F03`, `WP3-EDV-R01`
and `WP3-EDV-RR02`. Their implementation and tests are preserved; this rework
does not reopen, weaken or redefine them. Closure authority for R1A/R1B/R1C
remains with the next independent Controller Gate.

## 2. One authoritative evaluation instant

The grant transaction now follows this order:

```text
run FOR UPDATE
→ exact Job FOR SHARE
→ exactly four control epochs FOR SHARE in (scope_kind, scope_id) order
→ evaluated := database clock
→ static Job/entity/READ endpoint validation
→ scope/subject + Credential graph + temporal boundary evaluation at evaluated
→ grant_at := database clock
→ require grant_at < valid_until
→ authority expiry = min(grant_at + nominal authority, valid_until)
→ guarded run transition using lease_expires_at > grant_at
→ immutable decision evidence
→ structured identity-bound grant
```

`platform.evaluate_call_control_facts(job_id, scope_grant_id, evaluated_at)` is
the production resolver used by the grant and deterministic tests. It has no
run/epoch lock, transition, evidence insert or authority return. `PUBLIC` is
revoked and `marketops_app` has no `EXECUTE`, so it is a testable resolver, not
a parallel authorization path. Database authority time is not replaced by JVM
time.

At fixed Credential successor instant `2030-01-01T00:00:00Z`, the real database
tests prove:

```text
T - 1 ms: old Credential selected; valid_until = T
T:        successor selected
T ± 1 s sampled every 10 ms: never old selected with valid_until > T
```

The same before/after and 201-point property proof is executed for a future
scope-grant start.

## 3. Cycle-complete Credential selection

Current effective ACCOUNT READ credentials form the candidate graph. Recursion
starts from every leaf, records the path, emits the row that repeats a node with
`is_cycle = true`, and stops expanding that path. A grant is possible only when:

```text
candidate_count > 0
leaf_count = 1
cycle_count = 0
reachable_count = candidate_count
```

This rejects the previously missed `A ↔ B` plus `C → A` graph even though `C`
is a visible leaf. A complete `C → B → A` chain selects `C`; a linear component
plus a disconnected cycle denies by reachability; independent leaves deny by
leaf count; and an account-level Job never promotes a `STORE_SET` credential.
All refusal tests assert `MO013`, a still-`LEASED` run, unchanged call sequence
and zero decision evidence.

## 4. Exact Java authority allowlist

The enforced production allowlist is:

| Protected operation | Sole allowed production class |
| --- | --- |
| invoke `AcquisitionPort.acquire` or a concrete implementation | `com.mimococo.marketops.marketplaceintegration.port.AuthorizedAcquisitionExecutor` |
| call `AcquisitionRequest.from` | `com.mimococo.marketops.marketplaceintegration.port.AuthorizedAcquisitionExecutor` |
| invoke `CallAuthorityGrant` constructor | `com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CallAuthorityGrantMapper` |

The acquisition-call rule checks assignability, so a direct call against a
concrete adapter cannot evade an interface-owner comparison. Mutation fixtures
cover outside-module calls/construction, inside-owner direct concrete-adapter
calls, inside-owner untrusted grant construction and a second package-private
request-factory caller. The conforming fixture routes through the sole executor.

## 5. Preserved contracts and evidence limit

- V0001–V0006 remain byte-identical to `origin/main`.
- Four control scopes, event-specific triggers, route inventory/`NO_ROUTE`, the
  membership guard, endpoint pin, closed boundary relation, structured grant,
  Raw ACLs, direct-write denial, `SECURITY DEFINER`, fixed `search_path`, final
  checkpoint CAS and F01/F03 concurrency proofs remain intact.
- There is one authority abstraction and no caller Credential input, bare-expiry
  token, public request constructor or parallel old resolver.
- The executed acquisition flow proves identity-bound request construction,
  fake-port expiry refusal and zero real outbound. It does not prove socket
  start, Provider behavior, credential retrieval or deployment.
- A later real-adapter Gate must subtract local monotonic round-trip time and a
  safety margin from database authority duration, and must refuse REAL_HTTP for
  unverified Provider/capability state.

## 6. Production-readiness classification

This package is production-grade for its bounded executable design-validation
authority: no known compromise path, stale functional comment, parallel legacy
authority or scaffold production identifier remains in the repaired scope. It
is not the complete WP-P0-003 runtime or a project-level production-complete
MarketOps product.

Project-level readiness remains gated by explicitly allocated work:

- scheduler/worker lifecycle, retry, rate limiting, circuit state,
  backpressure, replay, reconciliation and backfill engines;
- approved Secret Manager and S3-compatible object storage (`OQ-006`);
- real Ozon/Wildberries adapters, verified capabilities, quotas and native
  status semantics (WP-P0-005/WP-P0-006);
- public webhook/manual/file-upload authentication and authorization (`OQ-005`);
- controlled file import (WP-P0-003B);
- downstream idempotent domain and ledger effects (WP-P0-005…WP-P0-007);
- operator product views and authenticated recovery surfaces (WP-P0-008);
- platform-write Command Outbox (`INT-017`); and
- deployment, hosting, retention, backup, personal-data and cross-border legal
  readiness (`OQ-107`).

These are project/work-package allocations, not hidden omissions or compromise
implementations inside this bounded Controller rework.

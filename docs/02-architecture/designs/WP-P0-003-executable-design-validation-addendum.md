# WP-P0-003 — Executable Design Validation Addendum

```yaml
document_type: as_built_design_addendum
task: WP_P0_003_EXECUTABLE_DESIGN_VALIDATION_FINAL_TARGETED_REWORK
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
work_package: WP-P0-003
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: 6715b4d48ebbea7b3135455d0d5a587fed1e00d0
reviewed_input_tree: 5bf299242c3c35b905f378fbfd3b2012537afea3
verified_implementation_head: d620a8b9d951ded22698448244f73d82cbd899d3
verified_implementation_tree: c743415fb4967a990e1d7aa6311e9484fcb5655f
package_identity_authority: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
current_findings: WP3-EDV-F01, WP3-EDV-F02, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
design_approved: false
bounded_scope_quality: PRODUCTION_GRADE
project_production_complete: false
marketplace_outbound: NONE
secret_retrieval: NONE
production_write: DISABLED
next_gate: CONTROLLER_WP_P0_003_IMPLEMENTATION_BACKED_DESIGN_VALIDATION_RE_REVIEW
```

The implementation identity above binds the code, migrations and tests that
passed the complete backend verification. The final evidence-only commit cannot
self-embed its own Git hash or tree; live PR #16 metadata and its body therefore
carry the exact final package Head, tree and tested-merge identity.

## 1. Controller finding closure

| Finding | As-built correction | Executable evidence | Rework state |
| --- | --- | --- | --- |
| `WP3-EDV-F01` | `grant_call_authority` locks the run, locks the exact Job, derives the four scope keys, acquires exactly four epoch rows in deterministic order, and only then evaluates Job/organization/account/platform/endpoint, subject, scope grant, server-selected Credential and temporal boundaries. Metadata that commits first is observed; metadata ordered later waits behind the held locks. | `TC-CTRL-F01-A/B/C1/C2`, plus `TC-CTRL-420…422` | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-F02` | Job endpoint is mandatory and platform-pinned. The grant validates active READ authority, derives the unique effective ACCOUNT READ Credential rotation leaf, rejects ambiguity/`STORE_SET`/nonpositive nominal authority, records the complete identity graph, and returns a structured grant. `AcquisitionRequest` has no public constructor and is derived only by `AuthorizedAcquisitionExecutor`; an architecture mutation fixture proves outside construction/rebinding is rejected. | `TC-CTRL-400/418/419/430…433`, `TC-PORT-001…004`, `TC-ARCH-023`, `F-ARCH-023/024`, `TC-CTRL-500/501` | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-F03` | Checkpoint acknowledgement locks the expected checkpoint row and performs a final guarded CAS that rechecks run id/job/state/fence/owner/live lease and observation ownership at database actual time. | `TC-CTRL-F03-A/B`, plus `TC-CTRL-406…409/427/428` | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-R01` | V0007 states the executable one-statement data-modifying CTE contract for platform plus guard creation and retains deferred totality as the commit backstop. Source comments describe only functional behavior. | `TC-CTRL-302…304`; production-readiness comment scan | `IMPLEMENTED_AWAITING_CONTROLLER` |
| `WP3-EDV-RR02` | Reviewed input, verified implementation and final PR package are separate provenance identities. No prior Head/tree is labelled as the final package. | this header, evidence package, live PR #16 body | `IMPLEMENTED_AWAITING_CONTROLLER` |

No row in this table claims an independent Controller verdict. Closure authority
remains with the next Gate.

## 2. Authoritative call contract

The application role may execute exactly this grant surface:

```sql
platform.grant_call_authority(
    run_id uuid,
    expected_fence bigint,
    expected_lease_owner text,
    scope_grant_id uuid,
    nominal_authority interval,
    correlation_id text
) RETURNS platform.call_authority_grant
```

There is no caller Credential parameter. The returned value and the immutable
decision evidence bind at least:

```text
decision_id, job_id, run_id, fence_token, lease_owner,
platform_code, endpoint_id, credential_id, scope_grant_id, call_seq,
granted_at, call_authority_expires_at,
control_epoch_scopes, control_epoch_values, boundary_set_digest
```

The acquisition executor validates the authority deadline before constructing
the request. The request delegates every identity to the immutable structured
grant, so an expiry cannot be rebound to another Job, run, fence, endpoint or
Credential.

## 3. Serialization and fail-closed behavior

The grant transaction uses one ordering discipline:

```text
run FOR UPDATE
→ exact Job FOR SHARE
→ exactly four control epochs FOR SHARE in (scope_kind, scope_id) order
→ all point-of-use metadata and temporal evaluation
→ one-row guarded run transition and server-derived call sequence
→ immutable evidence insert
→ structured identity-bound grant
```

The Credential resolver accepts exactly one connected rotation chain with one
effective leaf. Zero candidates, multiple leaves, disconnected branches,
cycles and `STORE_SET` candidates all deny. The nominal interval and returned
deadline must both be strictly positive relative to `granted_at`.

Checkpoint acknowledgement uses the corresponding final-mutation discipline:
the exact checkpoint version is locked, then a single guarded update rechecks
the live run authority and evidence relationship. A wait that crosses the lease
deadline therefore denies with no cursor change.

## 4. Preserved accepted contracts

- V0001–V0006 are byte-identical to `origin/main`.
- Four control scopes remain authoritative; there is no GLOBAL epoch row.
- Every routed table retains three event-specific statement triggers.
- Route inventory and `NO_ROUTE` vocabulary remain machine checked.
- Membership guard FK, totality, row-count assertion and immutable platform set
  remain intact.
- The closed six-kind temporal relation and three-identity Raw model remain
  intact.
- Raw remains application `SELECT` + `INSERT` only; run, checkpoint and decision
  evidence remain direct-write denied.
- `SECURITY DEFINER`, fixed `search_path`, `PUBLIC` revoke and explicit
  application grants remain intact.
- There is no real Marketplace outbound, secret resolution, provider claim,
  deployment or production write.

## 5. Production-readiness classification

This package is production-grade for the bounded executable design-validation
authority it implements: no known compromise path, stale functional comment,
parallel legacy authority or scaffold production identifier remains in that
scope. It is not project-level production-complete and does not implement the
whole WP-P0-003 runtime.

Project-level readiness remains gated by explicitly allocated work:

- full scheduler/worker lifecycle, retry, rate limiting, circuit state,
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

These are project/work-package allocations, not hidden omissions or shortcuts
inside the bounded Controller rework.

# SLICE-V1-001-AMENDMENT-002 — Deferred Real Integration and Pre-Production Assurance Boundary

```yaml
document_type: additive_slice_contract_amendment
amendment_id: SLICE-V1-001-AMENDMENT-002
title: Deferred Real Integration and Pre-Production Assurance Boundary
slice: SLICE-V1-001

original_contract_path: docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md
original_contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5

accepted_prior_amendment: SLICE-V1-001-AMENDMENT-001
accepted_prior_amendment_sha256: 8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d

owner_route: B
status: PROPOSED_PENDING_EXACT_HUMAN_OWNER_ACCEPTANCE
change_class: CLOSURE_BOUNDARY_AND_RELEASE_SEQUENCING
product_feature_scope_change: NONE
engineering_acceptance_reduction: NONE
security_or_data_integrity_reduction: NONE

reserved_deferred_work_item_id: RELEASE-V1-001
reserved_deferred_work_item_title: V1 Real Integration, Capability Verification and Production Readiness
reserved_deferred_work_item_status: RESERVED_NOT_ACTIVATED
activation_prerequisite: EXACT_OWNER_DECISION_V1_FUNCTIONAL_IMPLEMENTATION_COMPLETE

deployment: NOT_AUTHORIZED
production_credentials: NOT_AUTHORIZED
real_provider_or_marketplace_calls: NOT_AUTHORIZED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot_enablement: NOT_AUTHORIZED
production_write_enabled: false
```

## 1. Contract defect and purpose

The original SLICE-V1-001 Contract makes real external-system, real-environment,
Gate-EV, Pilot and operator evidence part of Slice completion. The Human Owner
has selected Route B:

> complete the engineering implementation and all product functionality first;
> then perform real OIDC, Yandex, Object Storage, Ozon, Wildberries, AI,
> Gate-EV and Pilot integration and verification in a dedicated pre-production
> Release work item; any defect exposed by that integration must be fixed and
> reverified before production.

This Amendment does not waive those evidence obligations. It moves the exact
real-integration and production-readiness portions listed below from
SLICE-V1-001 engineering closure into mandatory `RELEASE-V1-001`.

This Amendment also fixes the meaning of “Fix after integration”:

```text
controlled pre-production integration
→ discover a production-readiness defect
→ root-cause fix
→ complete regression
→ repeat the real integration evidence
→ only then consider production authorization
```

It does **not** authorize production deployment followed by later repair.

## 2. Binding Owner decisions

### A2-001 — Separate engineering closure from production readiness

After this Amendment is accepted, SLICE-V1-001 may reach:

```text
ENGINEERING_IMPLEMENTATION_CLOSED
PRODUCTION_READINESS_DEFERRED_TO_RELEASE_V1_001
```

only after all of the following are true:

1. every non-deferred Acceptance obligation is satisfied;
2. every R1 and Supplemental R2 implementation finding is root-cause closed;
3. no unresolved BLOCKER or MAJOR implementation finding remains;
4. all required local, real-PostgreSQL, migration, security, browser, replay,
   recovery-model and synthetic-provider evidence passes on the exact final Head;
5. the Closure Snapshot identifies every deferred criterion and does not label
   it `VERIFIED`, `NOT_APPLICABLE` or real-provider-proven;
6. production writes, deployment, Gate EV, Gate E and Pilot remain disabled.

Engineering closure is not a claim that MarketOps is deployable, production-ready
or interoperable with the selected live providers/accounts.

### A2-002 — Real integration occurs after V1 functional implementation completion

Real external integration is deferred until the Human Owner publishes an exact
decision artifact declaring:

```text
V1_FUNCTIONAL_IMPLEMENTATION_COMPLETE
```

That declaration means the Owner-selected V1 functional Slice set has completed
its engineering implementation closures. It does not itself authorize staging,
credentials, provider calls, deployment or writes.

Only after that exact declaration may `RELEASE-V1-001` be activated through its
own accepted Release Contract and Execution Envelope.

### A2-003 — Development-time obligations are not deferred

Before `RELEASE-V1-001`, each functional Slice must still implement and prove,
where applicable:

- provider-neutral ports and platform adapters;
- current official-source review and a maintained Capability Matrix;
- request/response schemas, quota models, pagination, timeout and unknown-state
  handling as contract-tested engineering facts;
- immutable Raw custody, replay, idempotency and reconciliation;
- fail-closed authentication, authorization, Secret and outbound-destination
  boundaries;
- deterministic metrics, Guardrails, workflow and command state machines;
- local/CI, real PostgreSQL, browser, fixture, synthetic-provider and negative
  evidence;
- production-disabled configuration and Kill Switch behavior.

Mocks, fixtures and public documentation remain engineering evidence only. They
must never be relabeled as real provider/account/environment evidence.

### A2-004 — Exact deferred Acceptance portions

The following real or Owner evidence portions move to `RELEASE-V1-001`.
The retained engineering portions remain mandatory in SLICE-V1-001.

| Acceptance ID | Deferred to RELEASE-V1-001 | Retained in SLICE-V1-001 engineering closure |
| --- | --- | --- |
| `S1-AC-001` | approved real OIDC login and mandatory-MFA interoperability | signed-token validation, unauthenticated denial, audience/issuer/expiry/security negative tests |
| `S1-AC-003` | real IdP disable/revoke, session and step-up/reauthentication behavior | current-database disable/revoke enforcement, audit and sensitive-action policy tests |
| `S1-AC-005` | reviewed Terraform apply/readback in real Yandex pre-production and least-privilege identity proof | reproducible reviewed IaC, static/plan/runtime-bootstrap verification and fail-closed configuration |
| `S1-AC-006` | actual Yandex PostgreSQL PITR plus approved Object Storage retention/integrity restore drill | migration/recovery design, local restore/equivalence evidence and immutable storage controls |
| `S1-AC-007` | real alert-channel delivery, No-Data behavior, fault injection, operator acknowledgement and recovery | telemetry implementation, alert definitions, synthetic failure tests and committed runbooks |
| `S1-AC-008` | current official-source refresh plus real Ozon account read/PRICE_CHANGE capability evidence | Ozon adapter, registry, request/response classification and contract fixtures |
| `S1-AC-009` | equivalent real Wildberries account evidence with native asynchronous/partial/quarantine semantics | Wildberries-specific adapter and non-symmetry contract fixtures |
| `S1-AC-010` | real-account quota, pagination, freshness, timeout/unknown-result and Credential-scope readback | versioned registry facts, rate-limit/pagination/error models and local contract tests |
| `S1-AC-012` | approved real Object Storage integration, exact-byte readback and provider retention/integrity evidence | custody authority, content addressing, hash/length verification and S3-compatible integration tests |
| `S1-AC-023` | approved real AI provider projection-boundary and PII/Secret interoperability evidence | projection allowlist, PII/Secret negative tests and provider-neutral Gateway controls |
| `S1-AC-025` | real AI provider timeout, malformed answer, unavailability and degradation behavior | local transport/output failure tests and proof that deterministic Gates are not bypassed |
| `S1-AC-026` | Human Owner approval of golden diagnostic usefulness using the integrated product and representative facts | structured-output validity, evidence-reference enforcement and explicit uncertainty behavior |
| `S1-AC-031` | exact Ozon Gate-EV envelope and bounded real write/readback/audit evidence | command authority, idempotency, unknown-result handling and synthetic Ozon protocol evidence |
| `S1-AC-032` | separate exact Wildberries Gate-EV envelope and bounded native real evidence | command authority and synthetic WB asynchronous/partial behavior |
| `S1-AC-033` | Gate-EV-bounded real restore/compensate evidence on both platforms | conditional restore, concurrency safety, no-blind-overwrite and synthetic compensation evidence |
| `S1-AC-038` | human operator execution of integrated outage, backlog, replay, AI failure, unknown-write and database/object-restore drills | complete runbooks and executable local/synthetic recovery behavior |
| `S1-AC-040` | exact Pilot cohort, users, Stores, Capabilities, Policy limits, monitoring window and rollback/kill criteria | schema, controls and fail-closed behavior needed to record and enforce a future Pilot |

`S1-AC-039` remains required for the exact SLICE-V1-001 engineering release Head.
`RELEASE-V1-001` must independently define and pass its own exact-Head CI,
security, integration and production-readiness gate.

`S1-AC-041` is not deferred. Merge, deployment and production enablement remain
distinct authorizations, and all shipped configuration remains production-write
disabled.

### A2-005 — Mandatory RELEASE-V1-001 evidence set

`RELEASE-V1-001` must not close until it has exact, redacted, attributable
evidence for all applicable items below:

1. approved OIDC login, MFA, disable/revoke, session and step-up;
2. real Yandex pre-production apply/readback, workload identities, networking,
   logging, monitoring and runtime configuration;
3. PostgreSQL PITR and Object Storage retention, integrity and restore;
4. real Ozon capability, read acquisition, quota, error and PRICE_CHANGE facts;
5. real Wildberries capability and its native asynchronous/partial/error facts;
6. approved AI provider interoperability, projection protection and degradation;
7. separate Gate-EV bounded write/readback/restore evidence for Ozon and WB;
8. Owner-approved golden diagnostic usefulness;
9. human operator alert and recovery drills;
10. exact Pilot definition and controlled readiness evidence;
11. all production-readiness defects found during integration, their root-cause
    fixes, complete regression and successful repeated evidence.

### A2-006 — Defects found during real integration

A discrepancy discovered in `RELEASE-V1-001` is classified before disposition:

```text
IMPLEMENTATION_DEFECT
PROVIDER_OR_ACCOUNT_FACT_CHANGE
CONFIGURATION_OR_DEPLOYMENT_DEFECT
CONTRACT_DEFECT
DOCUMENTATION_DRIFT
```

No discrepancy may be dismissed as “expected integration noise” when it causes:

- authentication or authorization bypass;
- Secret/PII exposure;
- incorrect business facts or fake precision;
- data loss, duplicate effect or unrecoverable state;
- unsafe external write, unknown result or unsafe restore;
- failed monitoring/recovery outcome;
- a core Acceptance failure.

All such defects block production until fixed and reverified. Calendar pressure
does not lower this Gate.

### A2-007 — RELEASE-V1-001 authority boundary

`RELEASE-V1-001` requires a separate exact Release Contract that specifies:

- exact pre-production environment and data boundary;
- credentials, account/Store scope and Secret custody;
- permitted real reads and their quotas;
- separately authorized Gate-EV envelopes for each real write;
- observation windows, abort conditions and Kill Switches;
- evidence custody and redaction;
- rollback/recovery;
- Owner, Operations, Security and Controller responsibilities.

This Amendment alone authorizes none of those actions.

Gate EV does not authorize Pilot use. Pilot readiness does not authorize general
production. Production deployment and Gate E remain separate exact
authorizations.

### A2-008 — Accepted late-integration risk

The Human Owner accepts that deferring real integration until functional
implementation completion may reveal provider-, environment- or account-specific
defects late and may require material rework before production.

The project must reserve time for that rework. No fixed date or sunk development
cost can convert an unresolved integration defect into an accepted risk without
a new exact Owner decision.

### A2-009 — Closure status vocabulary

For the deferred rows, the accepted Closure Snapshot must use an explicit status
equivalent to:

```text
OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001
```

It must not use:

```text
VERIFIED
EXECUTABLY_VERIFIED
NOT_APPLICABLE
REAL_PROVIDER_PROVEN
PRODUCTION_READY
```

The Closure Snapshot must retain the exact deferred Acceptance ID, required later
evidence, activation prerequisite and production-blocking effect.

## 3. Production-blocking invariants

Until `RELEASE-V1-001` and every later required Deployment/Production Change Gate
are accepted and complete:

```text
no production deployment
no production database migration
no production Credential access
no real production business data import
no public Pilot
no Gate E
no production feature-flag enablement
no autonomous or human-triggered Marketplace write
production_write_enabled = false
```

Read-only product development, future functional Slice contracts and their
isolated/synthetic implementation may continue after SLICE-V1-001 engineering
closure.

## 4. Preserved invariants

This Amendment does not change:

- the original Contract bytes or Acceptance IDs;
- accepted Amendment-001;
- the R1 or Supplemental R2 Finding Sets;
- the obligation to close all current implementation defects;
- business formulas, Source of Truth, authority or security boundaries;
- immutable Raw, replay, idempotency, Guardrail, command and audit requirements;
- current official-document review during development;
- the requirement that unknown states fail closed;
- the distinction between engineering evidence and real external evidence;
- the requirement to fix every integration defect before production.

## 5. Rejected interpretations

```text
Real evidence is waived
Fixtures count as real-provider evidence
The product is production-ready after engineering closure
Deploy first and fix integration defects later
Use production as the integration environment
Enable reads or writes without a separate Release Contract
Use one Gate-EV envelope for both platforms
Treat Gate EV as Pilot or Gate E
Stop refreshing official API facts during development
Move known R1/R2 implementation defects into RELEASE-V1-001
Merge PR #21 unchanged and silently relabel its proposed statuses
```

## 6. Resulting sequence

```text
Accept exact Amendment-002
→ complete Supplemental R2 engineering rework
→ Controller R2 Final Closure Verification
→ Owner Formal Engineering Closure for SLICE-V1-001
→ exact Closure Snapshot with deferred-release statuses
→ continue remaining V1 functional Slices
→ exact Owner declaration V1_FUNCTIONAL_IMPLEMENTATION_COMPLETE
→ accept and execute RELEASE-V1-001
→ fix and reverify every real-integration defect
→ separate Deployment / Gate E / Pilot authorizations
→ production only after every required Gate passes
```

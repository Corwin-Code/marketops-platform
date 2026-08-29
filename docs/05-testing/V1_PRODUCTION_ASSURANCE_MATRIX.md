# V1 Production Assurance Matrix

```yaml
document_type: production_assurance_contract
product_version: V1
active_slice: SLICE-V1-001
review_style: RISK_DRIVEN
quality_policy: PRODUCTION_GRADE_NO_COMPROMISE
```

## 1. Purpose

This Matrix replaces review-by-ceremony with evidence proportional to the actual
failure surface. It does not reduce tests or production controls. It defines what
must be falsified before a Slice or Capability is accepted.

Evidence strength is classified independently from a model or reviewer opinion.

## 2. Evidence classes

| Code | Evidence class | Valid examples | Not sufficient alone |
| --- | --- | --- | --- |
| `SRC` | Primary source / contract | official provider docs, accepted product contract, exact repository source | agent summary |
| `UNIT` | Unit/property/invariant | deterministic tests, mutation-sensitive rules | prose plan |
| `RDB` | Real database | Testcontainers/approved PostgreSQL, concurrency/crash tests | in-memory repository |
| `OBJ` | Object storage | approved provider or protocol integration, hash/readback/retention evidence | byte-array fake |
| `REAL_EXT` | Real external system/provider | Ozon/WB/IdP/Yandex/model provider evidence | fixture/sandbox claim without real proof |
| `SEC_NEG` | Security negative | authz bypass, secret/PII, upload, SSRF, injection, browser bundle tests | happy-path login |
| `REPLAY` | Replay/reconciliation | duplicate/late/out-of-order/replay and total reconciliation | one successful ingest |
| `BROWSER` | Browser E2E | production-like API/database/UI path | component snapshot |
| `PERF` | Performance/capacity | representative load/query/backfill evidence | local single-row timing |
| `DR` | Recovery/disaster drill | database/object restore, worker recovery, kill switch | written rollback only |
| `OPS` | Operator runbook drill | executed runbook with observable result | runbook file existence |
| `AUDIT` | Audit trace | end-to-end actor/evidence/command/readback chain | log line only |

## 2a. SLICE-V1-001 merged engineering evidence state

```yaml
assessed_at: 2026-08-30
assessed_against: MAIN_DB92CF2F_POST_MERGE
controller_engineering_final_gate: PASS
frozen_findings_closed: 13
owner_formal_closure: PENDING
detail: docs/07-phase-evidence/SLICE-V1-001/acceptance-status.md
executable_evidence: docs/07-phase-evidence/SLICE-V1-001/executable-evidence.md
post_merge_evidence: docs/07-phase-evidence/SLICE-V1-001/post-merge-closure-sync.md
closure_snapshot: docs/07-phase-evidence/SLICE-V1-001/CLOSURE-SNAPSHOT-DRAFT.md
```

This implementation-fact section does not amend the evidence classes or
requirements below. Controller engineering closure and protected merge do not
establish pending external/Owner/Gate evidence or production release authority.

| Class | Merged engineering evidence | Remaining boundary |
| --- | --- | --- |
| `SRC` | Immutable original Contract, accepted Amendment-001, preserved ADRs, Final Head/tree, tested merge and actual squash identity are bound. | Human Owner acceptance of exact Closure Snapshot bytes remains pending. |
| `UNIT` | Exact Final Head: two independent full backend runs with 846 unit/architecture tests each; 373 Python and 196 frontend tests; unchanged coverage gates pass. | No additional engineering boundary identified by Final Gate. |
| `RDB` | Exact Final Head: 374 real database integration tests in each full run; PG17 application/managed/representative paths plus standard PG18 compatibility. | Real Yandex managed bootstrap/PITR remains external. |
| `OBJ` | Actual filesystem and local HTTP adapter tests, exact hash/length verification, immutable DB custody and retention IaC. | Approved Yandex store, real retention/IAM operation and provider recovery remain unverified. |
| `REAL_EXT` | No real business provider or cloud account used. Synthetic verification fixtures are explicitly scoped. | OIDC, Marketplace, Yandex and model provider evidence remains pending; production registry defaults stay UNVERIFIED. |
| `SEC_NEG` | Signed-token/live-scope refusal, cross-store access denial, bounded outbound requests, symlink/redirect/response bounds, AI grounding and browser export integrity. Final Head Security CI and aggregate CodeQL pass; 26 alerts fixed and five exact Owner-authorized dispositions persist. | Reassessment triggers remain binding; real external authentication/provider evidence is separate. |
| `REPLAY` | Exact Final Head full runs verify real PG17 stored-Raw replay, parser/missing-object refusal, crash after fact commit and repeat with zero duplicate fact/source call. | Real source coverage remains external. |
| `BROWSER` | 11 Final Head Chromium scenarios cover signed JWT/SQL evidence→approval→command→readback, export, new-login recovery and local database outage. | Real identity/Marketplace interoperability remains pending. |
| `PERF` | PG17 representative 5,000-SKU/360,000-order profile, query plans, 488,000-record asynchronous export, explicit thresholds and settings. | Owner cohort and deployed capacity are unproven; no production throughput claim. |
| `DR` | Executed isolated PG17 dump/restore, migration/privilege validation, missing-object refusal and exact-byte recovery. | Real Yandex PITR, deployment rollback and environment restore remain pending. |
| `OPS` | Failure-drill index maps runbooks to executed local faults. Private signals and bounded telemetry transport/No Data are tested. | Actual alert creation, channel delivery, support acknowledgement and staging drill. |
| `AUDIT` | DB authority, immutable attempt/readback/approval/import/verification evidence, actor-bound browser/service paths and exact merge identity. | Real Gate-EV evidence and Human Owner Formal Closure remain pending. |

The original Maker evidence assessment is preserved at the reviewed PR Head.
Local evidence does not replace REAL_EXT, Owner approval, Gate EV or Gate E.
See the [post-merge closure index](../07-phase-evidence/SLICE-V1-001/post-merge-closure-sync.md)
for exact identities and the historical
[final handoff](../07-phase-evidence/SLICE-V1-001/rework-r1/final-handoff.md)
for preserved C3 and v1.1 provenance.

## 3. Risk dimensions

Every Deep Review and Final Gate explicitly assesses applicable dimensions:

1. Product outcome and user workflow;
2. authority/source-of-truth integrity;
3. data correctness, idempotency, late/unknown state and replay;
4. money/profit precision and versioning;
5. concurrency, lease/fencing and crash windows;
6. authentication, authorization, Secret/PII and file intake;
7. external provider volatility, quota and degraded operation;
8. platform write, unknown result, Readback, restore and blast radius;
9. AI grounding, privacy, structured output and deterministic-boundary bypass;
10. migration/backfill/compatibility and rollback;
11. observability, alerting, runbook and disaster recovery;
12. frontend accessibility, safe state representation and browser behavior;
13. performance/capacity and operating cost where material;
14. traceability and release-state truthfulness.

A non-applicable dimension is marked `N/A` with rationale. It is not silently
omitted.

## 4. Minimum Slice 1 assurance coverage

| Area | Required evidence classes | Required failure cases |
| --- | --- | --- |
| OIDC/MFA/RBAC | SRC, REAL_EXT, SEC_NEG, BROWSER, AUDIT | unauthenticated, disabled user, wrong Store/action scope, stale session/step-up |
| Secret/PII | UNIT, SEC_NEG, BROWSER | DB/log/trace/error/browser/AI/file export leakage |
| Worker/acquisition | UNIT, RDB, REPLAY, OPS | duplicate, 429, 5xx, timeout, stale lease/fence, crash-before/after Raw, backlog |
| Raw/Object Storage | RDB, OBJ, REPLAY, DR | wrong hash, already present mismatch, missing/orphan object, read failure, restore |
| Product mapping | UNIT, RDB, BROWSER | duplicate barcode, one-to-many conflict, unknown ID, effective-time change |
| Excel/CSV intake | UNIT, RDB, SEC_NEG, BROWSER, REPLAY | malformed/type/size/malware, duplicate hash, partial reject, schema drift, replay |
| Metrics/profit | UNIT/property, RDB, REPLAY | missing COGS, late fee/return, estimated vs canonical, decimal/rounding, version change |
| AI | UNIT, REAL_EXT, SEC_NEG, BROWSER | PII/Secret, prompt injection, nonexistent metric, malformed output, timeout/provider outage |
| Workflow/policy | UNIT, RDB, SEC_NEG, BROWSER, AUDIT | expired/rejected approval, wrong scope, stale recommendation, policy override/expiry |
| `PRICE_CHANGE` | SRC, UNIT, RDB, REAL_EXT, REPLAY, BROWSER, OPS, AUDIT | duplicate, timeout, partial/native failure, unknown result, readback mismatch, later external change, kill switch |
| UI | BROWSER, PERF, SEC_NEG | stale/estimated/unknown mislabeled, accessibility, API error, session expiry |
| Deployment/recovery | SRC, REAL_EXT, DR, OPS | DB/object restore, deployment rollback, provider outage, credential revoke |

## 5. Gate EV — Bounded Real-Write Verification Authorization

Before any real write is used to generate assurance evidence, Gate EV requires:

- explicit Human Owner authorization;
- exact Platform, opaque Account/Store reference, Capability and SKU allowlist;
- one-time or time-bounded window plus maximum price delta and cumulative
  exposure;
- current official-source and real-account Capability evidence;
- deterministic Guardrails and a passing Dry Run;
- supervised operator, abort owner, manual stop and global/scoped Kill Switch;
- captured pre-state, Readback and Restore/Compensate procedure;
- unknown-result/manual-resolution behavior;
- complete Audit and durable redacted evidence-retention plan.

Gate-EV verdicts are exactly:

```text
AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION
CHANGES_REQUIRED
BLOCKED_BY_EXTERNAL_CAPABILITY
BLOCKED_EVIDENCE_INCOMPLETE
```

The authorization is consumed or expires with its named operation/window. It
does not authorize general Pilot scope, unattended scheduling, broad Policy,
production release or any unnamed write.

## 6. Gate E — Controlled-write Capability Enablement

A write Capability is independently enabled per Platform/Account/Store/Scope only
when all are true:

- required real-write evidence was generated under a valid prior Gate-EV
  authorization for the exact scope;
- exact official and real-account Capability evidence is current;
- deterministic Guardrails and permission tests pass;
- Dry Run and stale-state refusal pass;
- command idempotency and transaction/outbox atomicity pass;
- timeout/unknown-result behavior is resolved by status/readback before retry;
- successful execution converges to independent Readback;
- restore/compensate has real bounded proof;
- audit and Kill Switch are complete;
- Pilot Cohort and blast-radius limits are recorded;
- no BLOCKER/MAJOR finding remains;
- Human Owner explicitly enables the exact scope.

Gate E consumes the bounded verification evidence and is the only Gate that may
approve ongoing controlled Pilot production release. Code merge, Slice
completion or Gate EV does not automatically enable the Capability.

## 7. Evidence retention

Evidence index entries record:

```text
contract / acceptance ID
source Head and tested merge identity
command or external test identity
UTC time and environment
result and reviewer
artifact/hash or durable external reference
redaction classification
known limitation / expiry / re-verification date
```

Never commit a credential, Buyer PII, unredacted production payload or a mutable
external link as the only proof.

## 8. Review verdict constraints

- Passing CI is necessary but not sufficient.
- A model claim is never test evidence.
- Fixture evidence cannot be promoted to `REAL_EXT`.
- Missing in-scope evidence is a blocker; it is not silently deferred.
- External evidence needed for Gate EV or production enablement may remain open
  during implementation when code remains fail-closed and the exact consuming
  Gate is recorded.
- Findings use only `BLOCKER`, `MAJOR`, `MINOR` or `INFORMATIONAL` and cite an
  exact source/test/evidence gap.

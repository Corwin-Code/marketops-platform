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

## 2a. SLICE-V1-001 evidence state

```yaml
assessed_at: 2026-08-27
assessed_against: LOCAL_CHECKPOINT_ONLY
detail: docs/07-phase-evidence/SLICE-V1-001/acceptance-status.md
executable_evidence: docs/07-phase-evidence/SLICE-V1-001/executable-evidence.md
```

| Class | State | What exists | What is missing |
| --- | --- | --- | --- |
| `SRC` | `SATISFIED` | The Contract, the accepted ADRs and the exact repository source. | — |
| `UNIT` | `SATISFIED` | 324 unit and architecture tests, including 65 boundary rules each with a deliberately invalid fixture. | — |
| `RDB` | `SATISFIED` | 197 integration tests against real PostgreSQL 18.4, of which 45 exercise the write path through the same functions the application calls, as the application role. | — |
| `OBJ` | `PARTIAL` | Hash, length and provenance per object; read-back verification; filesystem and S3-compatible adapters; compliance-lock configuration. | No approved object store was contacted. |
| `REAL_EXT` | `ABSENT` | Nothing. | No marketplace, identity provider, model provider or cloud account was contacted. Every capability and provider row is `UNVERIFIED`, which is why no call is reachable. |
| `SEC_NEG` | `SATISFIED` | Authorization refusals asserted against the running backend; bundle isolation; repository secret scan; AI output rejection of ungrounded, unrecognised and instruction-shaped claims. | — |
| `REPLAY` | `SATISFIED` | Idempotent fact keys, digest-keyed metric values, and a database trigger refusing a marketplace call during replay. | — |
| `BROWSER` | `PARTIAL` | 8 browser tests against the real backend and built console, including sign-in through to subject diagnosis. | The full chain in one browser run needs seeded operating data and a real identity provider. |
| `PERF` | `ABSENT` | Indexes for the queue and subject queries. | No representative data set and no environment to measure against. |
| `DR` | `PARTIAL` | Crash and lease-recovery behaviour proven against a real database; restore controls configured in reviewed infrastructure code. | The restore drill has never run, because there is nothing to restore. |
| `OPS` | `PARTIAL` | Eight runbooks committed, five of them new for this slice. | None has been executed by support personnel. |
| `AUDIT` | `SATISFIED` | Actor, evidence, decision, command and readback chain is complete and append-only, with the digest of the reviewed facts carried from proposal to decision to gate. | — |

Two classes are `ABSENT` and neither can be moved by engineering work: both need
an Owner-authorized act against a real external system.

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

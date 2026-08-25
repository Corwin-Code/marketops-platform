# WP-P0-003 — Durable Ingestion Control Plane & Immutable Raw Evidence

## 1. Metadata

| Field | Value |
| --- | --- |
| ID | WP-P0-003 |
| Title | Durable Ingestion Control Plane & Immutable Raw Evidence |
| Phase | Sprint 0 / Phase 0 |
| Status | DESIGN_FINALIZATION_REQUIRED |
| Authorization | DESIGN_ONLY |
| Risk | HIGH |
| Controller | GPT-5.6 Sol Pro / current ChatGPT Project |
| Designer / Maker | Claude Cowork / Claude Code |
| Repository writer | Mac Codex |
| Final merge authorization | Human Owner |
| Target branch | `main` |
| Design status | FINALIZATION_REQUIRED / NOT_FULLY_APPROVED |
| Design evidence | Frozen Design v1.11 candidate + `docs/02-architecture/designs/WP-P0-003-executable-design-validation-addendum.md` |
| Implementation-backed validation result | VERIFIED |
| Bounded validation authorization | CLOSED |
| Full implementation authorization | PROHIBITED |

PR #16 merged the bounded implementation-backed design-validation tranche after
independent Controller acceptance and separate Human Owner authorization. Its
validation result is verified and that one-time bounded authorization is closed.
The frozen Design candidate and as-built addendum are evidence for the current
Design-finalization Gate; they are not a fully approved Design of Record and do
not authorize remaining implementation, a new migration, an external
connection, a Secret, production data or a production write.

## 2. Business and Operator Outcome

The intended outcome is one auditable internal ingestion mainline that can
accept a future Marketplace Adapter acquisition, durably preserve the exact Raw
evidence, and safely resume, retry, backfill and replay without cursor data loss
or duplicate logical effects.

Tech/Data operators must eventually be able to:

- register and inspect Schedule, Manual, backfill and replay work;
- distinguish queued, leased, running, retry-wait, blocked, succeeded and
  terminal-failure states;
- inspect generic Job Run, Error Queue, Replay and Dead-letter state, invoke a
  bounded recovery command and follow its audit linkage;
- inspect cursor/checkpoint, Freshness, attempt, queue, lease, schema-observation
  and safe-error facts;
- replay already-saved Raw evidence with zero Marketplace outbound calls; and
- prove which exact Raw object and hash led to a processing result.

These are Design obligations and target outcomes, not current runtime claims.

## 3. Requirement Closure Contract

`WP-P0-003` owns only the bounded closure described below. A non-`FULL` row
retains the whole source requirement as `ACTIVE_CONTROL` or `PLANNED` under the
named later owner. Planning and Design do not verify a source requirement.

| Requirement | Closure model | WP-P0-003 target subset | Excluded or remaining boundary | Later owner / remaining Gate |
| --- | --- | --- | --- | --- |
| D-03 | MULTI-WP | Remaining internal PostgreSQL Task/Worker obligation after the verified WP-P0-001 Modular Monolith foundation | INT-017 platform-write Command Outbox | WP-P0-003 acceptance Gate closes the remaining worker subset; no later owner after verification |
| D-04 | PARTIAL | Immutable Raw evidence | Inventory and Financial Ledgers | WP-P0-007 |
| HR-01 | MULTI-WP | Durable Raw contract/runtime preserves exact returned response/report/event bytes for successes and business-meaningful failures with request metadata, hash, schema version, source time, ingestion time and provenance | Every real source path must later prove immutable evidence conformance; only transport/connectivity failure with no returned source bytes may use a failure-record-only path | WP-P0-003B, WP-P0-005, WP-P0-006 and WP-P0-007 |
| HR-02 | MULTI-WP | Trigger/job/Raw/cursor/replay idempotency | Adapter, Core and Ledger effects | WP-P0-005, WP-P0-006 and WP-P0-007 |
| INT-001 | STRUCTURE_ONLY | Platform-neutral acquisition port and envelope | Real Ozon/Wildberries Adapter and vendor HTTP | WP-P0-005 and WP-P0-006 |
| INT-004 | PARTIAL | Persisted Schedule/Manual trigger and internal push-envelope intake contract | Public webhook, authenticity and authenticated public manual surface | WP-P0-005/WP-P0-006 plus the runtime IAM Work Package selected after OQ-005 |
| INT-006 | PARTIAL | Generic Cursor/Offset/Page/Date Window/None strategy and safe checkpoint/CAS contract | Endpoint-specific pagination semantics | WP-P0-005 and WP-P0-006 |
| INT-007 | PARTIAL | Generic Account + Endpoint + opaque Credential reference/identity rate-limit scope, persistence and backpressure contract | Verified platform quotas and response semantics; no Secret retrieval or real quota guessing | WP-P0-005 and WP-P0-006; OQ-006 remains OPEN |
| INT-008 | PARTIAL | Generic timeout, backoff, jitter, retry-budget and circuit-state behavior integrated with job state | Platform error taxonomy | WP-P0-005 and WP-P0-006 |
| INT-009 | MULTI-WP | Generic job/Raw/cursor/replay deduplication | Downstream business effects | WP-P0-005, WP-P0-006 and WP-P0-007 |
| INT-010 | PARTIAL / MULTI-WP | Immutable Raw request metadata, exact returned bytes/object reference/hash, schema version, source/ingestion time, provenance, source kinds and read verification for successful and business-meaningful failed calls | Actual API/report/push/manual-file integrations and approved provider acceptance; only no-source-byte transport/connectivity failures permit failure-record-only treatment | WP-P0-003B, WP-P0-005 and WP-P0-006; OQ-006 gates provider acceptance |
| INT-011 | PARTIAL | Generic schema fingerprint/observation and unknown-field capture | Concrete file and platform schemas | WP-P0-003B, WP-P0-005 and WP-P0-006 |
| INT-012 | PARTIAL | Generic error/replay runtime and replay without redownload | Source-specific reconciliation | WP-P0-005, WP-P0-006 and WP-P0-007 |
| INT-013 | PARTIAL | Backfill manifest/partition/resume engine and synthetic fixtures | Real 90–180-day source history evidence | WP-P0-005, WP-P0-006 and WP-P0-007 |
| INT-014 | MULTI-WP | Mandatory Source/Ingestion/Processing time contract and persistence | Propagation through every future fact path | All source and domain Work Packages |
| INT-019 | OUT_OF_SCOPE | No controlled file import capability in this Work Package | CSV/Excel/platform-report import, file-upload security and importer workflow | WP-P0-003B |
| INT-021 | STRUCTURE_ONLY | Opaque native status/error and unknown-state envelope | Actual native mappings and versioned semantic evidence | WP-P0-005, WP-P0-006 and WP-P0-007 |
| ADM-002 | MULTI-WP | Job Schedule and Backfill operator contract | WP-P0-002 already verified Feature Flag/Capability metadata; real capability evidence remains open | WP-P0-005 and WP-P0-006 |
| ADM-004 | PARTIAL / MULTI-WP | Generic Job Run, Error Queue, Replay and Dead-letter state, recovery-command contract, audit linkage and single runtime authority | Data Quality/Admin product view, cross-domain UX and final Phase 0 management closure; authenticated/public operator surface | WP-P0-008; OQ-005 and the future runtime IAM Work Package gate the authenticated/public surface |

The split of `INT-019` is recorded by
`docs/00-governance/DR-0002-split-controlled-file-import-from-wp-p0-003.md`.
It does not change an accepted ADR or reopen WP-P0-001/WP-P0-002.

## 4. Scope

The Design must cover the smallest complete foundation that preserves the
coupled correctness boundary among worker leases, cursor acknowledgement,
immutable Raw durability, idempotency, replay and backfill:

1. one platform-neutral acquisition/ingestion contract and result envelope;
2. internal Schedule and Manual trigger registration plus an internal push-event
   intake contract only;
3. PostgreSQL task/job state, lease, heartbeat, expiry, fencing, retry scheduling,
   attempt, cancellation and terminal-fact contracts;
4. Cursor, Offset, Page, Date Window and None/Unknown checkpoint strategies;
5. generic Account + Endpoint + opaque Credential reference/identity rate
   limiting, timeout, backoff, jitter, retry-budget, circuit-state and
   backpressure behavior tied to worker state, without Secret retrieval or real
   quota guessing; distinct Credential scopes/identities under the same Account
   and Endpoint must not be silently merged unless future verified platform
   evidence explicitly permits it;
6. idempotency identities for trigger, job, source page/event/file identity, Raw
   object and replay;
7. immutable Raw exchange/event/report request metadata, exact returned bytes,
   hash/length, schema version, source/ingestion/processing time, provenance and
   opaque object reference for successes and business-meaningful failures;
8. an Object Storage port and production-capability contract, with concrete
   provider decisions gated by OQ-006;
9. attributable error/exception state, generic Job Run/Error Queue/Replay/
   Dead-letter state, selective replay from stored evidence and reconciliation
   hooks;
10. immutable backfill requested scope/window, partition progress, resume and
    outcome/difference summary;
11. safe internal operator query/recovery-command contracts and audit linkage,
    while leaving the Data Quality/Admin product view, cross-domain UX and final
    Phase 0 management closure to WP-P0-008; and
12. forward-only migration, architecture, runbook, metric and evidence plans.

Design must stay at contract, state, authority, transaction, security and
falsification boundaries. It must not preselect final SQL, Java classes, indexes,
routes, provider SDKs or resilience libraries.

## 5. Non-goals

- No real Ozon/Wildberries HTTP/SDK Adapter, Credential use, capability
  verification or Marketplace outbound traffic.
- No public webhook endpoint or platform-specific webhook authenticity.
- No CSV/Excel/file-upload/importer implementation; those belong to
  `WP-P0-003B — Controlled File Import & Source Intake Security`.
- No Product/Listing/Core normalization, Inventory/Finance Ledger, Mart, product
  table or Daily Report.
- No INT-017 platform-write Command Outbox, Recommendation/Approval/Readback
  chain or production platform-write path.
- No deployment, production data, real PII, Secret Manager retrieval, provider
  onboarding or production write.
- No permanent large-payload byte storage in PostgreSQL as a substitute for an
  approved S3-compatible object store.
- No ninth PostgreSQL schema and no change to V0001–V0006.

## 6. Dependencies and Accepted Decisions

- completed and closed WP-P0-001 repository/CI/modular-monolith foundation;
- completed and closed WP-P0-002 Organization/Account/Store/Warehouse, Service
  Account, Credential-reference, Endpoint/Capability, Feature Flag and audit
  metadata foundation;
- ADR-0001 Modular Monolith, PostgreSQL worker and S3-compatible Raw direction;
- ADR-0002 immutable Raw/idempotent replay direction;
- ADR-0003 read-first and production-write refusal;
- ADR-0004 Controller–Maker–CI–Owner Gate model;
- the existing eight schemas and immutable V0001–V0006 history; and
- OQ-006 before concrete Object Storage/Secret Final Design approval,
  Implementation authorization or bounded Raw acceptance.

No accepted ADR or Owner Decision is changed by this Work Package record.

## 7. Module, Authority and Source-of-Truth Boundary

- `marketplaceintegration` is the single owner of acquisition contracts, job
  orchestration, cursor/checkpoint, source policy and Raw intake coordination.
- `adminobservability` consumes module contracts to expose safe internal views
  and request safe recovery commands; it is not a second executor, writer,
  scheduler, worker or persistence authority.
- `organizationaccount` and `identityaccess` provide existing references and
  evaluations through public module APIs; cross-module Repository access remains
  prohibited.
- `shared` remains a dependency leaf and receives only stable cross-cutting
  primitives.
- `raw` is immutable evidence truth; `ops` is mutable operational workflow truth;
  `platform` is endpoint/cursor/policy metadata truth; `staging` is
  reconstructible parse/validation state.
- `core`, `ledger` and `mart` are unchanged.

There may be only one scheduler/worker, cursor writer, replay path and object-store
authority for this capability.

The structured authority declaration is binding:

| Capability | Sole executor / writer | Consumer-only module | Authority mode |
| --- | --- | --- | --- |
| Job scheduler/worker | marketplaceintegration | adminobservability | SINGLE |
| Cursor/checkpoint writer | marketplaceintegration | adminobservability | SINGLE |
| Replay/dead-letter recovery command executor | marketplaceintegration | adminobservability | SINGLE |
| Raw object-store intake coordinator | marketplaceintegration | adminobservability | SINGLE |

## 8. Binding Correctness Invariants

1. A worker acts only under a live lease and current fencing token.
2. Lease renewal, transition, attempt and terminal facts use database time and
   compare-and-set/fencing semantics.
3. A stale or expired worker cannot advance cursor, finalize a job, mutate the
   current attempt or publish derived success.
4. Exact Raw bytes are durably stored and hash-verified before the corresponding
   cursor/checkpoint is acknowledged.
5. Cursor advancement and the immutable database Raw-reference commit are ordered
   so an acknowledged cursor never points beyond durable evidence.
6. Duplicate trigger/source identity/replay cannot create duplicate logical
   processing effects.
7. Every successful call and every business-meaningful failed call that returns
   response/report/event bytes preserves safe request metadata, the exact
   returned bytes, hash, schema version, source time, ingestion time and
   provenance as immutable Raw, regardless of HTTP or business status. Only a
   transport/connectivity failure with no returned source bytes may use an
   attributable failure-record-only path; failure Raw must never be discarded
   or downgraded because of HTTP or business status.
8. Schema changes and unknown fields are observed; unknown values are never
   coerced to success.
9. Replay reads saved Raw evidence and performs zero Marketplace outbound calls.
10. Backfill scope/window is immutable after start; resume continues from
    committed partition/checkpoint state.
11. Unknown, disabled, expired or scope-mismatched Service Account/organization/
    account/endpoint metadata denies work.
12. Production platform writes remain impossible and disabled.

The Design must provide an executable state/transaction/failure model that can
falsify these invariants. Prose intent alone is insufficient for its Controller
Design Gate.

## 9. Raw, Object Storage, Hash and Schema Boundary

- Hash exact received bytes, never a reserialized representation.
- Preserve returned response/report/event bytes for both successful and
  business-meaningful failed calls; HTTP/business status never downgrades Raw.
- Permit failure-record-only treatment only for a transport/connectivity failure
  that returned no source bytes.
- Store immutable object identity/reference, hash algorithm/value, byte length,
  media/source type, request metadata, schema version, source time, ingestion
  time and provenance in the database.
- Integrity-check object reads before replay or processing.
- Keep object references opaque and tamper-checked; never persist or log bucket
  credentials or signed URLs.
- Design explicit orphan-object and missing-object reconciliation.
- Permit a fake/in-memory store only in test source; it is not real-provider
  evidence.
- Classify a protocol-compatible test service as `INTEGRATION_TEST`; only an
  approved Integration/Staging provider after OQ-006 may produce
  `REAL_PROVIDER_OR_EXTERNAL_SYSTEM` evidence.

The application path may append/read Raw evidence but must not update or delete
it. Mutable workflow and processing state belongs outside immutable Raw rows.

The structured Raw outcome contract is binding:

| Source outcome | Returned source bytes | Required durable treatment |
| --- | --- | --- |
| Successful call | YES | Immutable Raw exact bytes plus request metadata, hash, schema version, source time, ingestion time and provenance |
| Business-meaningful failed call | YES | Immutable Raw exact bytes plus request metadata, hash, schema version, source time, ingestion time and provenance; never failure-record-only |
| Transport/connectivity failure | NO | Attributable failure-record-only treatment is permitted |

The structured rate-limit identity contract is binding:

| Dimension / rule | Required contract |
| --- | --- |
| Account | Opaque Account identity |
| Endpoint | Provider-neutral Endpoint identity |
| Credential | Opaque Credential reference/identity; no Secret retrieval |
| Partitioning | Distinct Credential scopes/identities under the same Account and Endpoint must not be silently merged unless future verified platform evidence explicitly permits it |
| Quota semantics | No real quota guessing; WP-P0-005/WP-P0-006 retain verified platform quotas and response semantics; OQ-006 remains OPEN |

## 10. Security, No-leak and Intake Boundary

- No Secret, token, production payload, Buyer PII, signed object URL, Raw payload
  or stack trace may be printed or committed.
- Fixtures in this public repository are synthetic or explicitly approved and
  redacted.
- Operator errors expose stable safe codes and correlation IDs, not object
  internals or unsafe failure detail.
- Object Store and PostgreSQL roles use least privilege; the application path
  receives no Raw update/delete privilege.
- Public webhook authenticity and file-upload abuse controls are explicitly out
  of scope, not mocked or represented as verified.
- OQ-005 blocks any public webhook/manual/file-upload runtime surface.
- OQ-006 remains OPEN; no provider, region, identity, key or retention behavior
  may be guessed.

## 11. Migration and Compatibility Boundary

- Use forward-only V0007+ migrations; never edit V0001–V0006.
- Use the existing schemas only; do not create a ninth schema.
- Prove both a fresh database and V0001–V0006 upgrade path.
- Keep schema changes transactional or define explicit roll-forward recovery.
- Perform no remote call or production backfill from Flyway.
- Preserve all WP-P0-002 APIs/data and closed provenance.

This section constrains future Design. This governance-only activation creates no
migration and authorizes none.

## 12. Observability, Recovery and Runbook Contract

The Design must cover safe, low-cardinality telemetry for queue/ready age,
active/expired leases, attempt result, retry/circuit/rate-limit state, cursor age,
Freshness, object persist/read/hash mismatch, schema drift, replay/backfill and
orphan/missing-object reconciliation.

Required recovery/runbook cases include:

- stuck/expired lease and stale worker;
- object store unavailable or hash mismatch;
- partial page and crash window;
- replay without outbound;
- backfill pause/resume/cancel;
- cursor investigation and reconciliation;
- schema drift and queue growth/backpressure; and
- database restore plus object-store consistency check.

Telemetry must not expose Secrets, payloads, signed URLs or unbounded native
status/error values.

## 13. Evidence and Falsification Plan

| Evidence class | Required future proof |
| --- | --- |
| STATIC_SOURCE_PROOF | Single authority, no vendor SDK leakage, no INT-017 path and no Secret/payload logging |
| UNIT_TEST | State transitions, failure taxonomy, retry budget, cursor strategies, reference/hash and unknown-field behavior |
| PROPERTY/INVARIANT_TEST | Duplicate trigger, replay convergence, monotonic fencing and immutable backfill scope |
| ARCHITECTURE_TEST | Module internals, `shared` leaf, one worker authority and vendor SDK confinement |
| REAL_DATABASE | PostgreSQL fresh/upgrade, constraints, leases/CAS/fencing, concurrent workers, Raw append-only privileges |
| INTEGRATION_TEST | PostgreSQL plus S3-compatible protocol service, failure injection, crash recovery and no-outbound replay |
| FAKE_CREDENTIAL_ZERO_OUTBOUND | Synthetic Adapter covers positive/negative/duplicate/late/unknown/replay cases with zero Marketplace network |
| SECURITY_TEST | Reference tampering, redaction, scope denial, safe errors and privilege negatives |
| PERFORMANCE_OR_LOAD | Queue/backpressure, lease contention, batch size and retry-amplification limits |
| REAL_PROVIDER_OR_EXTERNAL_SYSTEM | Approved Object Storage/Secret evidence only after OQ-006; `NOT_RUN` before approval |
| PACKAGE_OR_PROVENANCE | Exact base/head/tree, migration hashes, reports, SBOM/lock and changed-file manifest |

Mandatory failure-window proofs include crash before object persistence; object
persistence followed by failed Raw-reference commit; Raw commit followed by
failed cursor CAS; duplicate cursor retry; stale worker after lease expiry;
missing/corrupt object during replay; partial page/backfill; schema drift;
rate-limit/timeout/retry exhaustion; a business-meaningful failed call with
returned response/report/event bytes that must remain immutable Raw rather than
a failure-record-only entry; a transport/connectivity failure with no returned
source bytes; distinct Credential identities under one Account/Endpoint that
must not be silently merged; and process restart with queued/running/retry-wait
work.

Governance validation can bind these structured fields and reject controlled
counter-mutations, but it does not replace independent semantic Controller
review of the complete contract.

## 14. Risks and Owner Gates

Primary risks are cursor acknowledgement before Raw durability, stale-worker
commit after lease loss, duplicate downstream effects, object-reference/hash
tampering, evidence inflation from fake storage, capacity/retry amplification
and later divergence from platform-specific semantics.

Owner-controlled Gates remain:

- OQ-006 blocks concrete Object Storage/Secret Final Design approval,
  Implementation authorization and bounded INT-010/HR-01 Raw acceptance. The
  required answer must cover provider, region/locality, workload identity,
  encryption, versioning/immutability, retention/deletion, backup/restore,
  quotas/cost and audit evidence.
- OQ-005 blocks a public operator, webhook or file-upload surface.
- OQ-101/OQ-102 block actual onboarding and verified platform capability facts.
- OQ-106 blocks concrete ERP/WMS/accounting source integration planning.
- OQ-107 blocks deployment readiness and production hosting/privacy/legal claims.

The structured Owner Gate allocation is binding:

| Gate | Status | Allowed before answer | Blocked before answer | Later owner |
| --- | --- | --- | --- | --- |
| OQ-005 | OPEN | Internal provider-neutral worker and operator contract Design | Any authenticated/public operator, webhook, manual-trigger or file-upload runtime surface | Future runtime IAM Work Package selected by the Controller |
| OQ-006 | OPEN | Provider-neutral object and opaque Credential-reference contract Design | Concrete Object Storage/Secret Final Design approval, Implementation authorization, bounded Raw acceptance, Secret retrieval and real quota assumptions | Human Owner + Security, then WP-P0-005/WP-P0-006 platform evidence |

Planning keeps all of these questions OPEN and requests no Secret or production
data.

The post-merge follow-up ledger also retains
`WP3-EDV-BC-R4B-01` as
`MANDATORY_BEFORE_FIRST_REAL_ADAPTER_GATE`: the owning-module predicate must use
the exact package root `com.mimococo.marketops.marketplaceintegration` before
the first real `AcquisitionPort` or `ObjectStoragePort` Adapter Gate. There is
no current production bypass, so this is recorded but is not implemented or
represented as closed by the governance transition.

## 15. Design Deliverables and Controller Gate

The Controller must reconcile the frozen Design candidate, executable
validation addendum, merged source, migrations, dependencies, tests, closed
WP-P0-001/WP-P0-002 records, accepted ADRs and remaining Work Package scope.
Design finalization must define, without authorizing further implementation:

- the one module/data/transaction authority and public contract boundary;
- state machines, transitions, CAS/fencing and failure ownership;
- Raw-before-cursor ordering and crash-window recovery;
- idempotency identities and later downstream-effect boundary;
- provider-neutral object capabilities, integrity and reconciliation;
- security/least-privilege/no-leak and internal-only surface boundaries;
- forward migration/upgrade/roll-forward compatibility;
- observability, runbooks, capacity/backpressure and recovery;
- Requirement → Test → Evidence mappings and falsification cases; and
- every open assumption, OQ Gate and Decision Request.

The exact current Gate is
`CONTROLLER_WP_P0_003_DESIGN_FINALIZATION`. The Controller must actively attempt
to falsify durability ordering, stale-worker exclusion, idempotency,
provider-evidence maturity, scope confinement and traceability claims, then
decide the next bounded Design/implementation scope. It must not infer full
Design approval or further implementation authorization from PR #16.

Current stop declaration:

```text
STATUS: DESIGN_FINALIZATION_REQUIRED
AUTHORIZATION: DESIGN_ONLY
DESIGN_STATUS: FINALIZATION_REQUIRED / NOT_FULLY_APPROVED
IMPLEMENTATION_BACKED_DESIGN_VALIDATION: VERIFIED
BOUNDED_VALIDATION_AUTHORIZATION: CLOSED
FULL_IMPLEMENTATION_AUTHORIZATION: PROHIBITED
MARKETPLACE_OUTBOUND: PROHIBITED
SECRET_OR_PRODUCTION_DATA: PROHIBITED
PRODUCTION_WRITE: DISABLED
NEXT_GATE: CONTROLLER_WP_P0_003_DESIGN_FINALIZATION
```

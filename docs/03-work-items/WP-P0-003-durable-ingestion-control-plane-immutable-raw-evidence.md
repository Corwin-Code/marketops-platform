# WP-P0-003 — Durable Ingestion Control Plane & Immutable Raw Evidence

## 1. Metadata

| Field | Value |
| --- | --- |
| ID | WP-P0-003 |
| Title | Durable Ingestion Control Plane & Immutable Raw Evidence |
| Phase | Sprint 0 / Phase 0 |
| Status | READY_FOR_DESIGN |
| Authorization | DESIGN_ONLY |
| Risk | HIGH |
| Controller | GPT-5.6 Sol Pro / current ChatGPT Project |
| Designer / Maker | Claude Cowork / Claude Code |
| Repository writer | Mac Codex |
| Final merge authorization | Human Owner |
| Target branch | `main` |
| Design artifact | NOT_YET_PRODUCED |
| Implementation authorization | PROHIBITED |

This record activates only the Design Gate after the governance Pull Request is
independently reviewed, authorized by the Human Owner and merged. It does not
contain a Design artifact and does not authorize implementation, a migration,
an external connection, a Secret, production data or a production write.

## 2. Business and Operator Outcome

The intended outcome is one auditable internal ingestion mainline that can
accept a future Marketplace Adapter acquisition, durably preserve the exact Raw
evidence, and safely resume, retry, backfill and replay without cursor data loss
or duplicate logical effects.

Tech/Data operators must eventually be able to:

- register and inspect Schedule, Manual, backfill and replay work;
- distinguish queued, leased, running, retry-wait, blocked, succeeded and
  terminal-failure states;
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
| HR-01 | MULTI-WP | Durable Raw contract/runtime for generic acquisition | Every real source path must later prove immutable evidence conformance | WP-P0-003B, WP-P0-005, WP-P0-006 and WP-P0-007 |
| HR-02 | MULTI-WP | Trigger/job/Raw/cursor/replay idempotency | Adapter, Core and Ledger effects | WP-P0-005, WP-P0-006 and WP-P0-007 |
| INT-001 | STRUCTURE_ONLY | Platform-neutral acquisition port and envelope | Real Ozon/Wildberries Adapter and vendor HTTP | WP-P0-005 and WP-P0-006 |
| INT-004 | PARTIAL | Persisted Schedule/Manual trigger and internal push-envelope intake contract | Public webhook, authenticity and authenticated public manual surface | WP-P0-005/WP-P0-006 plus the runtime IAM Work Package selected after OQ-005 |
| INT-006 | PARTIAL | Generic Cursor/Offset/Page/Date Window/None strategy and safe checkpoint/CAS contract | Endpoint-specific pagination semantics | WP-P0-005 and WP-P0-006 |
| INT-007 | PARTIAL | Generic Account + Endpoint rate-limit scope, persistence and backpressure contract | Verified platform quotas and response semantics | WP-P0-005 and WP-P0-006 |
| INT-008 | PARTIAL | Generic timeout, backoff, jitter, retry-budget and circuit-state behavior integrated with job state | Platform error taxonomy | WP-P0-005 and WP-P0-006 |
| INT-009 | MULTI-WP | Generic job/Raw/cursor/replay deduplication | Downstream business effects | WP-P0-005, WP-P0-006 and WP-P0-007 |
| INT-010 | PARTIAL / MULTI-WP | Immutable Raw metadata, exact bytes/object reference/hash, source kinds and read verification | Actual API/report/push/manual-file integrations and approved provider acceptance | WP-P0-003B, WP-P0-005 and WP-P0-006; OQ-006 gates provider acceptance |
| INT-011 | PARTIAL | Generic schema fingerprint/observation and unknown-field capture | Concrete file and platform schemas | WP-P0-003B, WP-P0-005 and WP-P0-006 |
| INT-012 | PARTIAL | Generic error/replay runtime and replay without redownload | Source-specific reconciliation | WP-P0-005, WP-P0-006 and WP-P0-007 |
| INT-013 | PARTIAL | Backfill manifest/partition/resume engine and synthetic fixtures | Real 90–180-day source history evidence | WP-P0-005, WP-P0-006 and WP-P0-007 |
| INT-014 | MULTI-WP | Mandatory Source/Ingestion/Processing time contract and persistence | Propagation through every future fact path | All source and domain Work Packages |
| INT-019 | OUT_OF_SCOPE | No controlled file import capability in this Work Package | CSV/Excel/platform-report import, file-upload security and importer workflow | WP-P0-003B |
| INT-021 | STRUCTURE_ONLY | Opaque native status/error and unknown-state envelope | Actual native mappings and versioned semantic evidence | WP-P0-005, WP-P0-006 and WP-P0-007 |
| ADM-002 | MULTI-WP | Job Schedule and Backfill operator contract | WP-P0-002 already verified Feature Flag/Capability metadata; real capability evidence remains open | WP-P0-005 and WP-P0-006 |

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
5. generic Account + Endpoint rate limiting, timeout, backoff, jitter,
   retry-budget, circuit-state and backpressure behavior tied to worker state;
6. idempotency identities for trigger, job, source page/event/file identity, Raw
   object and replay;
7. immutable Raw exchange/event/report metadata, exact-byte hash/length,
   source/ingestion/processing time, schema observation and opaque object reference;
8. an Object Storage port and production-capability contract, with concrete
   provider decisions gated by OQ-006;
9. attributable error/exception state, selective replay from stored evidence and
   reconciliation hooks;
10. immutable backfill requested scope/window, partition progress, resume and
    outcome/difference summary;
11. safe internal operator query/command contracts and audit linkage; and
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
  and commands; it is not a second scheduler, worker or persistence authority.
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
7. Every meaningful acquisition attempt has safe request metadata and either
   immutable evidence or an attributable failure record.
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
- Store immutable object identity/reference, hash algorithm/value, byte length,
  media/source type and provenance in the database.
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
rate-limit/timeout/retry exhaustion; and process restart with queued/running/
retry-wait work.

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

Planning keeps all of these questions OPEN and requests no Secret or production
data.

## 15. Design Deliverables and Controller Gate

The Designer must first cross-check current source, migrations, dependencies,
tests, closed WP-P0-001/WP-P0-002 records and accepted ADRs. The standalone
Design artifact must then define, without implementation:

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

The exact next Gate is independent Controller Design review. The Controller must
actively attempt to falsify durability ordering, stale-worker exclusion,
idempotency, provider-evidence maturity, scope confinement and traceability
claims. A Design that guesses OQ-006 or lacks an executable failure model receives
`CHANGES_REQUIRED` or `BLOCKED_BY_OWNER_DECISION`.

Current stop declaration:

```text
STATUS: READY_FOR_DESIGN
AUTHORIZATION: DESIGN_ONLY
DESIGN_ARTIFACT: NOT_YET_PRODUCED
IMPLEMENTATION_AUTHORIZATION: PROHIBITED
MARKETPLACE_OUTBOUND: PROHIBITED
SECRET_OR_PRODUCTION_DATA: PROHIBITED
PRODUCTION_WRITE: DISABLED
NEXT_GATE: CONTROLLER_DESIGN_REVIEW
```

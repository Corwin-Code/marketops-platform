# WP-P0-002 — Organization, Store, Warehouse & Credential Metadata

## 1. Metadata

| Field | Value |
| --- | --- |
| ID | WP-P0-002 |
| Title | Organization, Store, Warehouse & Credential Metadata |
| Phase | Sprint 0 / Phase 0 |
| Status | IMPLEMENTING |
| Authorization | APPROVED_FOR_IMPLEMENTATION |
| Risk | HIGH |
| Controller | GPT-5.6 Sol Pro / current ChatGPT Project |
| Designer / Maker | Claude Cowork / Claude Code |
| Repository writer | Mac Codex |
| Final merge authorization | Human Owner |
| Target branch | `main` |
| Design artifact | `docs/02-architecture/designs/WP-P0-002-organization-store-warehouse-credential-metadata-design.md` |
| Implementation authorization | APPROVED_FOR_IMPLEMENTATION |

This record is in its implementation stage: the Controller approved the design
artifact above for implementation. It still does not authorize Marketplace
connectivity, credential retrieval, production data or production writes.

## 2. Business Outcome

Design a platform-neutral, evolvable and auditable metadata foundation for the
operating entities and Marketplace access boundaries used by later Connector,
Raw, PIM, Order, Inventory and Finance Work Packages. The design must give those
consumers explicit identities and relationships for:

- Organization and Legal Entity;
- Marketplace Account and Store;
- Warehouse and Store-to-Warehouse service/fulfillment associations;
- authorization scopes and permission taxonomy;
- Service Account metadata and opaque Credential references;
- Endpoint, Capability and verification state;
- Feature Flag and Capability metadata.

The outcome is a production-grade design and bounded implementation contract. It
must not imply that a real Ozon or Wildberries connection, Credential or current
business inventory has been established.

## 3. Source Requirements and Planning Inputs

### 3.1 Source requirements

- IAM-001: Organization, Legal Entity, Account, Store and Warehouse identity and
  authorization-scope foundation.
- IAM-004: separate Read, Write, Finance, Ads and Credential Admin permissions.
- IAM-006: scoped and expiring Service Account metadata.
- IAM-007: auditable identity, permission and Credential-metadata changes.
- INT-002: Endpoint / Capability Registry foundation.
- INT-003: opaque Secret Manager references only; never plaintext Secret.
- ADM-001: controlled maintenance/query boundary for organization, account,
  store, warehouse, timezone and currency metadata.
- ADM-002: Feature Flag and Capability metadata; Job Schedule and Backfill remain
  outside this Work Package.
- ADR-0001: Modular Monolith and PostgreSQL baseline.
- ADR-0004: Controller–Maker–CI–Owner development model.
- Handoff Protocol: explicit inputs, outputs, failure states, acceptance and
  evidence before implementation authorization.

### 3.2 OQ-101 accepted Design constraints

The Controller accepted the following Human Owner planning input for Design:

- the current actual Legal Entity count is `1`, but the Domain must not enforce a
  maximum of one Legal Entity;
- actual Ozon/Wildberries Account and Store counts and cardinalities are not yet
  fixed;
- the common Domain must not assume a platform-independent one-to-one mapping;
- Warehouse count is unknown and the model must remain extensible;
- a Warehouse is not a strict child that can belong to only one Store;
- the generic fulfillment family must at least express Marketplace-fulfilled and
  Seller-fulfilled modes without guessing platform-specific facts;
- actual production Account, Store and Warehouse inventory remains an
  onboarding/acceptance input.

OQ-101 remains OPEN. These constraints satisfy topology input for Design only;
they do not close business onboarding or platform evidence.

## 4. Ownership and Association Semantics

The generic model must separate ownership from operational service association:

```text
Ownership hierarchy:
Organization → Legal Entity → Marketplace Account → Store

Warehouse:
Legal Entity-owned operational node

Store ↔ Warehouse:
configurable service / fulfillment association

Platform-specific cardinality:
UNKNOWN until verified platform evidence exists
```

Design must reject a strict generic tree of
`Organization → Legal Entity → Marketplace Account → Store → Warehouse`.
Platform extensions may add verified constraints later without narrowing the
common model through assumptions.

## 5. Requirement Closure Contract

The statuses below describe the intended closure boundary when WP-P0-002 is
eventually implemented and accepted. This Design-activation PR does not verify
any requirement.

| Requirement | WP-P0-002 closure | Current WP delivers | Current WP does not deliver | Later closure responsibility | Acceptance boundary |
| --- | --- | --- | --- | --- | --- |
| IAM-001 | PARTIAL | Identity, lifecycle and association metadata plus scope attachment points | User authentication, runtime authorization enforcement and row-level access | A future Controller-selected runtime IAM Work Package | Referential integrity and scope metadata pass; runtime access remains unverified |
| IAM-004 | PARTIAL | Read/Write/Finance/Ads/Credential Admin taxonomy and no-amplification rules | Runtime RBAC/ABAC decision enforcement | Future runtime IAM authorization implementation | Taxonomy and negative invariants pass; no claim of enforced user permissions |
| IAM-006 | PARTIAL | Service Account identity, purpose, scope, allowed-source metadata, lifecycle and expiry | Token issuance, Secret retrieval and runtime network enforcement | Future IAM/Credential runtime integration after required decisions | Expired/disabled metadata fails closed; no runtime account claim |
| IAM-007 | PARTIAL | Metadata-change audit contract for identities, scopes, Service Accounts and Credential references | Login/session audit and unrelated sensitive-operation audit | Future runtime IAM and feature Work Packages | Metadata changes are attributable and append-only; other audit domains remain planned |
| INT-002 | PARTIAL | Registry structure and `UNKNOWN`/`UNVERIFIED` verification semantics | Guessed or actual Ozon/Wildberries endpoint facts | WP-P0-005 and WP-P0-006 populate verified platform evidence | Unverified capability cannot enable behavior; registry structure is traceable |
| INT-003 | PARTIAL | Opaque Credential reference and non-disclosure contract | Secret Manager selection, connectivity or Secret retrieval | Future Credential runtime integration after OQ-006 | No plaintext Secret in database, API, UI, logs, fixtures or evidence |
| ADM-001 | FULL | Controlled maintenance/query boundary for Organization, Account, Store, Warehouse, timezone and currency metadata | General-purpose unauthenticated public mutation surface | Closed in WP-P0-002 only when the safe maintenance boundary and evidence pass | Maintenance is auditable and fail closed under the current no-auth foundation |
| ADM-002 | PARTIAL | Feature Flag and Capability metadata with production-write default disabled | Job Schedule, Backfill execution and real platform Capability population | WP-P0-003 owns Job Schedule/Backfill; WP-P0-005/006 own platform evidence | Metadata invariants pass; scheduling and real capabilities remain unverified |

Traceability status remains `PLANNED`. A `PARTIAL` row must not become
`VERIFIED` merely because WP-P0-002 completes.

## 6. Scope

Design must cover all of the following as one coherent metadata foundation:

1. Organization, Legal Entity, Marketplace Account, Store and Warehouse
   identity, native keys, lifecycle and status metadata;
2. timezone and currency metadata with explicit validity rules;
3. configurable Account/Store/Warehouse associations and effective dating;
4. generic Marketplace-fulfilled and Seller-fulfilled metadata without guessed
   platform mapping;
5. authorization-scope taxonomy and attachment points;
6. Read, Write, Finance, Ads and Credential Admin permission taxonomy;
7. Service Account owner, purpose, scope, source restriction, expiry, disablement
   and last-use metadata;
8. opaque Credential secret reference, owner, scope, expiry and rotation metadata;
9. Endpoint / Capability Registry structure, version and verification state;
10. Feature Flag and Capability metadata whose unknown state fails closed;
11. append-only metadata change audit responsibility;
12. ADM-001 controlled maintenance/query contract;
13. Requirement → Test → Evidence traceability.

## 7. Non-goals

- login, MFA, session or token implementation;
- complete runtime RBAC/ABAC enforcement;
- real Secret Manager selection, connectivity or retrieval;
- S3/Object Storage selection or integration;
- real Ozon/Wildberries HTTP clients, connectivity or capability claims;
- Raw, Cursor, Replay, Backfill or worker implementation;
- PIM, Order, Inventory, Return or Finance facts;
- guessed endpoint, role, subscription, rate-limit or paid-feature facts;
- Marketplace writes or production Feature Flag activation;
- production credentials, production inventory or production data;
- a ninth PostgreSQL schema without explicit Controller Design Review.

## 8. Inputs, Outputs and Failure States

### 8.1 Inputs

- the accepted OQ-101 topology facts in Section 3.2;
- Baseline Requirement IDs in the Requirement Closure Contract;
- existing WP-P0-001 schemas, least-privilege and no-auth security boundary;
- accepted ADRs and governance controls;
- synthetic fixtures only;
- verified primary platform evidence only when a later platform Work Package
  supplies it.

No Secret, Buyer PII, production payload or guessed platform fact is an input.

### 8.2 Outputs

- a production-grade, platform-neutral metadata model and lifecycle contract;
- controlled maintenance/query contract with its security boundary;
- append-only audit model and evidence contract;
- authorization-scope, permission and Service Account metadata contracts;
- opaque Credential reference contract;
- Endpoint/Capability Registry and Feature Flag metadata contracts;
- additive migration, least-privilege, observability and recovery design;
- deterministic test and evidence plan mapped to acceptance criteria.

### 8.3 Required failure states

Design must define detection, safe behavior, audit and recovery for at least:

- an invalid cross-Organization relationship;
- an invalid or unknown authorization scope;
- an expired or disabled Service Account;
- a plaintext Secret or Secret-leakage attempt;
- an unknown or unverified capability;
- an invalid Store/Warehouse association or effective-date overlap;
- a duplicate identity or native-key conflict;
- an attempt to activate production-write capability while production writes are
  disabled.

Unknown and unverified states fail closed; they are not coerced to `false`,
`available` or a platform default without preserving their meaning.

## 9. Security and Data Boundaries

### 9.1 Maintenance surface must fail closed

The current foundation has no runtime authentication. Claude Design must therefore
show how ADM-001 maintenance remains local/internal/admin-only and fail closed.
It must not expose an unauthenticated mutation API usable from a public or
production network. The Design must describe the current boundary and its future
runtime IAM integration, but this Planning record does not decide the final API.

### 9.2 Credential safety

- persist only opaque references and non-secret metadata;
- prohibit plaintext Secret in database columns, migrations, API payloads, UI,
  logs, exceptions, fixtures and evidence;
- separate Read, Write, Finance, Ads and Credential Admin scopes;
- reject implicit privilege amplification and production-write activation;
- keep OQ-006 OPEN until Secret Manager and storage decisions are authorized.

### 9.3 Schema boundary

`audit` is a conceptual responsibility, not an approved PostgreSQL schema. The
existing foundation schemas are exactly:

```text
iam / platform / raw / staging / core / ledger / mart / ops
```

WP-P0-002 does not authorize a ninth schema. Claude Design must first map
responsibilities to existing schemas. Any proposed new schema requires explicit
rationale and Controller Design Review before implementation authorization.

## 10. Acceptance Criteria

WP-P0-002 may be accepted only when the approved implementation and evidence
satisfy every applicable criterion:

1. cardinalities remain configurable and no platform-independent 1:1 assumption
   is encoded;
2. identity/native-key uniqueness and referential integrity are deterministic;
3. cross-Organization relationships are rejected and audited;
4. permission/scope assignment cannot amplify privilege implicitly;
5. Service Account expiry, disablement, purpose and scope fail closed;
6. no plaintext Secret appears in persistence, API/UI, logs, errors, fixtures or
   evidence;
7. `UNKNOWN` and `UNVERIFIED` capabilities fail closed and retain provenance;
8. `production_write_enabled` remains `false`, and no metadata flag can override
   it;
9. every metadata change records actor/source, time, entity, action and evidence;
10. audit records are append-only through the approved application boundary;
11. migrations are additive, preserve V0001 and have deterministic clean-database
    evidence;
12. database roles retain object-level least privilege with no blanket/default
    grants;
13. architecture boundaries prevent platform clients or Secrets from leaking into
    the common Domain;
14. deterministic positive, negative, failure-state and mutation tests map to
    requirement evidence;
15. no hidden product, integration, credential or production scope is added;
16. ADM-001 maintenance/query behavior is demonstrably inaccessible as a public
    unauthenticated mutation surface.

## 11. Migration, Rollback and Observability Expectations

Claude Design must specify:

- additive Flyway migrations only; `V0001` is immutable;
- object-level least privilege and no blanket or default grants;
- compatibility across phased deployment and seeded synthetic fixtures;
- forward recovery for metadata corrections rather than destructive rollback;
- explicit handling for duplicate/native-key migration conflicts;
- structured audit and diagnostic signals without Secret leakage;
- metrics/logs for invalid association, unknown capability, expired Service
  Account and denied production-write attempts;
- operational evidence that preserves actor/source and correlation identifiers.

## 12. Design Deliverables and Required Evidence

Claude must return a standalone Design artifact containing:

- verified technology/platform facts with primary sources and verification date;
- proposed module, package, data, API and maintenance-surface changes;
- data model, constraints, ownership/association and effective-date semantics;
- lifecycle, sequence and failure/recovery model;
- permission, Service Account, Credential reference and Capability contracts;
- migration/backfill/compatibility plan;
- security/privacy/secret analysis;
- observability and operational recovery plan;
- deterministic test matrix mapped to Requirement IDs and acceptance criteria;
- assumptions, unresolved Unknowns and Decision Requests.

The Design must not include implementation commits. After Design return, the
independent Controller reviews the actual artifact under the Design Gate.

## 13. Risks and Constraints

- Risk is HIGH because identity, scope, credentials and future write capability
  boundaries affect every later Work Package.
- OQ-101 actual inventory, OQ-005 authentication, OQ-006 Secret Manager and
  OQ-102 platform capabilities remain OPEN.
- Volatile platform facts require current primary-source evidence and a
  last-verified date; absence stays `UNKNOWN`/`UNVERIFIED`.
- A completed WP-P0-001 design is prior foundation provenance, not authorization
  to implement WP-P0-002.
- No business rule, platform cardinality or production capability may be inferred
  from synthetic fixtures or model output.

## 14. Controller Gate

Current state:

```text
Status: IMPLEMENTING
Authorization: APPROVED_FOR_IMPLEMENTATION
Approved design SHA-256: 3e524c666e56b3d5fdecd6e2098a22d1bd9fd88711dd9c524858ca0cdd3859b2
Next authorized actor: Claude Designer / Maker, then independent Controller
Next authorized action: Deliver the bounded implementation candidate for CONTROLLER_IMPLEMENTATION_REVIEW
Implementation: PROHIBITED until APPROVED_FOR_IMPLEMENTATION — satisfied by the design verdict above
Production writes: DISABLED
```

The Controller Design verdict vocabulary is:

```text
APPROVED_FOR_IMPLEMENTATION
CHANGES_REQUIRED
BLOCKED_BY_OWNER_DECISION
BLOCKED_BY_EXTERNAL_CAPABILITY
```

The recorded verdict on Design v1.2 is `APPROVED_FOR_IMPLEMENTATION`. Only
`APPROVED_FOR_IMPLEMENTATION` on an exact Design artifact can authorize an
implementation task. Nothing in this Work Package authorizes Ready, merge,
Marketplace connection, credential retrieval or production writes.

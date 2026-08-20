# Open Questions Register

## Bootstrap decisions

| ID | Question | Needed by | Blocking | Owner | Status |
| --- | --- | --- | --- | --- | --- |
| OQ-001 | Which GitHub user/organization owns `marketops-platform`? | Repository creation | Yes | Human Owner | RESOLVED |
| OQ-002 | What company-controlled domain defines the Java root package? | WP-P0-001 implementation | Yes | Human Owner | RESOLVED |
| OQ-003 | What is the primary development OS and container runtime? | WP-P0-001 design approval | No | Human Owner | RESOLVED |
| OQ-004 | Is Codex formally enabled for rework/fix/verify? | First review cycle | No | Human Owner | RESOLVED |
| OQ-005 | Which authentication solution will be used for application users? | Runtime IAM and public ingestion surfaces | No for internal WP-P0-003 worker Design; yes for public webhook/manual/file-upload runtime | Owner + Controller | OPEN |
| OQ-006 | Which Secret Manager and S3-compatible object storage are approved for Integration/Staging/Production? | INT-003 / Raw Final Design, implementation and acceptance | No for planning/provider-neutral Design start; yes for concrete storage/Secret Final Design, Implementation authorization and bounded Raw acceptance | Owner + Security | OPEN |

## Sprint 0 business and platform questions from the Baseline

| ID | Question | Needed by | Blocking | Status |
| --- | --- | --- | --- | --- |
| OQ-101 | Actual Ozon/WB Store and Account count and fulfillment modes | WP-P0-002/005/006 | No for metadata Design; yes for onboarding/acceptance and platform WPs | OPEN |
| OQ-102 | Available API roles, subscriptions and advertising permissions | Capability Matrix v1 | No for metadata Design; yes for verified platform capability evidence | OPEN |
| OQ-103 | First-version cost model for purchase, packaging, warehouse labor, tax and overhead | Finance model | Yes | OPEN |
| OQ-104 | Business windows for completed order, refusal and return | Order/return mapping | Yes | OPEN |
| OQ-105 | Internal Barcode/SKU data quality and duplicate policy | Product identity | Yes | OPEN |
| OQ-106 | Existing ERP/WMS/accounting systems and export/API availability | Integration scope | No | OPEN |
| OQ-107 | Russia hosting, backup, personal-data and cross-border access legal confirmation | Staging/Production | Yes before production | OPEN |
| OQ-108 | Hero SKU and first experiment scope | Phase 1 | No for Phase 0 | OPEN |

## WP-P0-002 Planning dispositions — questions remain OPEN

### OQ-101 — topology input satisfied; actual inventory pending

```text
Planning disposition: Sufficient input for the current metadata Design Gate only.
Current Legal Entity count: 1.
Domain constraint: The model must not hard-code one Legal Entity, a common
  Account/Store 1:1 cardinality or Warehouse as a strict single-Store child.
Pending acceptance input: Actual Ozon/WB Account, Store, Warehouse and fulfillment
  inventory for onboarding and platform Work Packages.
Closure state: OPEN. Final business onboarding/acceptance still requires the
  actual inventory and verified platform evidence.
Secret handling: No Secret was requested or recorded.
```

### OQ-005, OQ-006 and OQ-102 — non-blocking for metadata Design

```text
OQ-005: OPEN. It does not block the platform-neutral identity/scope metadata
  Design, but it blocks later runtime IAM authentication and enforcement design.
OQ-006: OPEN. It does not block opaque Credential-reference metadata Design, but
  it blocks real Secret Manager retrieval and storage integration.
OQ-102: OPEN. It does not block an UNKNOWN/UNVERIFIED Capability Registry
  structure, but it blocks verified platform capability population and behavior.
```

None of these dispositions authorizes a guessed platform fact, real Credential,
Marketplace connection or production write.

## WP-P0-003 Planning dispositions — questions remain OPEN

### OQ-005 — internal Design allowed; public surfaces blocked

```text
Planning disposition: OPEN. It does not block the provider-neutral internal
  worker, lease, cursor, Raw, replay or backfill Design.
Blocked boundary: Any public webhook, public manual-trigger or file-upload
  runtime surface, including its caller authentication and reauthorization.
Later Gate: Runtime IAM Design/Implementation selected by a future Controller.
No authentication product or public route is selected by this disposition.
```

### OQ-006 — provider-neutral Design only before approved answer

```text
Planning disposition: OPEN. It does not block canonicalization or the start of a
  provider-neutral Design using opaque object references and fixed capability
  requirements.
Blocked boundaries: Concrete Object Storage/Secret Final Design approval,
  Implementation authorization, and bounded INT-010/HR-01 Raw acceptance.
Minimum Owner/Security input: Approved Secret Manager and S3-compatible provider;
  region/locality, workload identity, encryption, versioning/immutability,
  retention/deletion, backup/restore, quotas/cost and audit evidence.
Evidence boundary: Test-only fake/in-memory storage and protocol-compatible
  integration services are not REAL_PROVIDER_OR_EXTERNAL_SYSTEM evidence.
No provider, region, account, key, Credential or retention behavior is guessed.
```

### OQ-101, OQ-102, OQ-106 and OQ-107 — preserved later Gates

```text
OQ-101: OPEN. Generic Design may use platform-neutral IDs and synthetic topology;
  actual Ozon/WB Account, Store, Warehouse and fulfillment inventory remains an
  onboarding/platform-acceptance input.
OQ-102: OPEN. UNKNOWN/UNVERIFIED remains fail-closed; verified roles,
  subscriptions, quotas and capabilities remain WP-P0-005/006 evidence.
OQ-106: OPEN. The generic foundation and bounded split may proceed; actual
  ERP/WMS/accounting formats, ownership and source behavior are required when
  WP-P0-003B/WP-P0-007 are planned.
OQ-107: OPEN. Local/synthetic Design and verification may proceed; hosting,
  backup, personal-data, retention, cross-border and external-service legal
  confirmation are required before deployment/production readiness.
Secret handling: No Secret or production data was requested or recorded.
```

## Resolved bootstrap decisions

### OQ-003 — Primary development platform and local container contract

```text
Decision / answer: The primary development OS is macOS. Local container commands
  target a Docker-compatible CLI with Compose v2 support. WP-P0-001 does not
  mandate a specific runtime vendor.
Evidence or source: Human Owner platform direction, the approved WP-P0-001
  canonical design operational constraints, and Controller PR #4 rework verdict.
Effective date: 2026-08-14
Affected Work Packages / ADRs / Requirements: WP-P0-001, ADR-0001, G0, local
  developer setup and fresh-clone acceptance.
Migration or compatibility impact: No product or data migration. Implementation
  commands and tests remain vendor-neutral within the Docker-compatible Compose
  v2 contract.
Approver: Human Owner
```

### OQ-002 — Java root package and Maven groupId

```text
Decision / answer: com.mimococo.marketops
  This is the Java root package and Maven groupId.
Evidence or source: Human Owner decision, Controller approval, and naming baseline.
Effective date: 2026-08-14
Affected Work Packages / ADRs / Requirements: WP-P0-001, ADR-0001, naming baseline,
  traceability.
Migration or compatibility impact: No product or data migration. No Java source exists
  before this decision is recorded.
Approver: Human Owner
```

### OQ-004 — Codex rework and Git execution role

```text
Decision / answer: Codex is enabled for bounded PR rework/verification and is
  temporarily delegated mechanical Ready/merge execution under D-17, but cannot
  approve its own authored/repaired changes or bypass any gate.
Evidence or source: Explicit Human Owner instruction and DR-0001.
Effective date: 2026-08-12
Affected Work Packages / ADRs / Requirements: G0, WP-P0-001, D-12, D-16, D-17,
  ADR-0004
Migration or compatibility impact: No product/data migration. Governance and
  handoff contracts distinguish Owner authorization, independent Controller
  verdict and delegated Git execution.
Approver: Human Owner
```

### OQ-001 — Repository ownership and pre-production visibility

```text
Decision / answer: The personal GitHub account Corwin-Code owns
  https://github.com/Corwin-Code/marketops-platform. The repository is Public
  during pre-production under D-15 and must return to Private when real production
  go-live is reached, or earlier before confidential business material is committed.
Evidence or source: Live GitHub repository and Human Owner instruction.
Effective date: 2026-08-12
Affected Work Packages / ADRs / Requirements: G0, WP-P0-001, D-11, D-15, HR-06
Migration or compatibility impact: No application/data migration. Repository
  visibility, Rulesets and security controls must be revalidated when converting
  to Private.
Approver: Human Owner
```

## Resolution format

Every resolved question must record:

```text
Decision / answer
Evidence or source
Effective date
Affected Work Packages / ADRs / Requirements
Migration or compatibility impact
Approver
```

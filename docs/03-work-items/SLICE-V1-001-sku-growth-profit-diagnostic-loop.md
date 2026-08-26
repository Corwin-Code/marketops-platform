# SLICE-V1-001 — SKU Growth & Profit Diagnostic Loop

```yaml
document_type: production_delivery_slice_contract
slice_id: SLICE-V1-001
product_version: V1
status_after_reset_merge: CONTRACT_APPROVED
implementation_authorization_after_reset_merge: FULL_SCOPE_IMPLEMENTATION
delivery_risk: CRITICAL
controller: GPT-5.6 Sol Pro
maker: Claude Fable 5 / Claude Code
rework_agent: Codex
production_enablement: DISABLED_PENDING_FINAL_AND_CAPABILITY_GATES
first_controlled_write: PRICE_CHANGE
platforms: OZON_AND_WILDBERRIES
release_scope: ALLOWLISTED_PILOT_COHORT
```

## 1. Observable business outcome

A remotely authenticated operations user can open MarketOps and use real Ozon,
Wildberries and internal operating facts to answer, for an allowlisted SKU cohort:

- what changed in exposure, CTR, conversion, price, promotion, stock, returns,
  advertising and Contribution Profit;
- which facts are current, estimated, missing or low-confidence;
- what the most likely root cause is and what competing explanations remain;
- which action should be taken first, with evidence, expected impact and risk;
- whether an approved or Owner-policy-authorized price change was accepted by the
  Marketplace, read back correctly and later changed operating outcomes.

The primary structured UI must support the complete operating path. AI augments
analysis and recommendation; it is not the only UI or a second source of truth.

## 2. End-to-end path

```text
Secure public login
→ Store/SKU scoped access
→ Ozon + WB + internal source acquisition
→ immutable Raw + provenance
→ mapping + canonical facts + versioned metrics
→ data quality / freshness / confidence
→ cross-domain deterministic diagnosis
→ AI explanation and recommendation
→ Task / Approval or bounded Owner Policy
→ deterministic Commercial Guardrails
→ idempotent PRICE_CHANGE Command
→ platform-specific Ozon/WB execution
→ status convergence and Readback
→ Audit + restore/compensate + metric follow-up
→ production operating UI
```

## 3. In scope

### 3.1 Human identity and business authorization

- external OIDC login with mandatory MFA through the selected production IdP;
- MarketOps user profile, role, Store/Platform/Data/Action Scope, enable/disable,
  session/reauthentication boundary and attributable Audit;
- backend enforcement; frontend visibility is not authorization;
- step-up/recent-auth requirement for sensitive approval and production-write
  actions where the approved IdP/application flow supports it.

### 3.2 Production infrastructure required by the Slice

- Yandex Cloud `ru-central1` infrastructure-as-code and environment contract for
  application runtime, managed PostgreSQL, immutable Raw object storage, Secret
  references, audit/logging/monitoring and backup/restore;
- local/integration/staging/production configuration separation;
- no Kubernetes or multi-cloud in V1;
- no real Secret or production data in Git, logs, fixtures, PRs or chat.

### 3.3 Marketplace onboarding and Capability evidence

- at least one Owner-controlled Ozon Seller Account and one WB Seller Account;
- Secret references and least-privilege Read/Price-Write capability separation
  where platform/account facts permit;
- current official API Capability Matrix and account-level proof for required
  Slice reads and `PRICE_CHANGE` write/readback semantics;
- quota, pagination, freshness, native state, timeout/unknown-result and error
  behavior recorded with last-verified date;
- real platform calls only through the `marketplaceintegration` authority path.

### 3.4 Durable acquisition, Raw and processing

- finish/adapt the existing WP-P0-003 bounded authority into the Slice Shared
  Spine rather than creating a parallel worker;
- Scheduler/Manual internal trigger, lease, heartbeat, fencing, cursor/checkpoint,
  retry budget, rate limiting, backpressure, replay, reconciliation and bounded
  backfill needed by the Slice sources;
- Yandex Object Storage implementation of immutable exact-byte custody, verified
  hash/readback, opaque object reference and orphan/missing-object reconciliation;
- schema fingerprint, unknown fields/native states and safe error evidence;
- Raw-before-acknowledgement, replay-with-zero-redownload and duplicate-effect
  prevention.

### 3.5 Product and listing identity

- Internal SPU/SKU/Variant, Color, Size and Barcode needed for the pilot cohort;
- Ozon and WB native listing/variant identifiers preserved verbatim;
- versioned Mapping Candidate, confidence, conflict/duplicate queue, manual
  confirmation and effective-time history;
- unresolved or conflicting mapping blocks precise COGS/profit and any write;
- one internal SKU may map to multiple platform listings without erasing platform
  identity.

### 3.6 Thin cross-domain facts for diagnosis

Implement the production-grade subset needed to diagnose SKU growth and profit:

- Product/Listing state and Listing Health inputs;
- current and historical price/discount/promotion observations;
- FBO/FBS platform stock and internal physical/available stock input;
- order/completed/retained/settled sales facts sufficient for funnel and profit;
- cancellation/refusal/return facts and reason categories;
- platform finance/fee/settlement facts sufficient for Operational Contribution
  Profit and confidence;
- impressions, clicks, visits, conversion and other available funnel facts;
- campaign/spend/performance facts sufficient to diagnose advertising efficiency.

A platform-unavailable metric is `NOT_AVAILABLE`, never zero. Platform-native
objects/statuses remain traceable to Raw.

### 3.7 Internal operating facts and controlled intake

- productized manual entry fallback and Excel/CSV import for COGS/purchase cost,
  local warehouse stock and required finance/accounting facts;
- file hash, source/effective/import time, schema profile/version, preview,
  validation, mapping, duplicate handling, rejection report, audit, replay and
  reconciliation;
- content/type/size/malware safeguards appropriate to the deployment boundary;
- approved values/version history rather than silent overwrite;
- no technical one-off script as the only V1 intake path.

### 3.8 Deterministic facts, metrics and diagnosis

- canonical, versioned, reproducible definitions for the Slice metrics;
- at minimum: traffic/funnel facts when available, sales, retained sales windows,
  return rate, stock/freshness, COGS, fees, ad spend, Operational and Settled
  Contribution Profit, Margin, Minimum Price and Confidence;
- `Completed Sale`, `Retained Sale` and `Settled Sale` remain separate;
- Retained Sale supports 7d/14d/30d metric windows, with 30d as primary default;
- canonical versus estimated inputs and profit are visibly distinct;
- deterministic diagnosis rules for Data Blocked, Low Impression, Low CTR, Low
  Conversion, High Return, Stockout Risk, Negative Margin and related conditions;
- deterministic rule order before AI.

### 3.9 AI analysis and recommendation

- provider-neutral AI Gateway with approved external provider implementation;
- approved Data Projection, field allowlist, PII/Secret exclusion, prompt and
  output schema versioning;
- AI output separates Fact, Inference, Recommendation and Unknown;
- every stated Fact binds to canonical Metric/Evidence references;
- cross-domain root-cause analysis, competing hypotheses, recommended action,
  priority, expected impact, risk, validation horizon and confidence;
- malformed, hallucinated or ungrounded output is rejected or degraded;
- AI cannot directly create/approve/execute Marketplace Commands.

### 3.10 Operations workflow and Commercial Policy

- Recommendation, Evidence, Task, Assignment, Approval, Reject, Expire, Cancel,
  Policy Authorization, execution result and outcome linkage;
- versioned Owner-configurable Commercial Policy with global defaults and scoped
  overrides for strategy/lifecycle/Store/SKU where justified;
- Hero/Growth/Mature/Repair/Exit objective policy;
- deterministic Price Guardrails including data completeness/confidence,
  sellability, mapping, inventory safety, minimum price, minimum Contribution
  Profit/Margin, maximum single/daily change, cooldown, promotion conflict,
  permission, approval/policy scope, validity and pilot allowlist;
- default deny for missing/unknown policy or source state;
- Policy change audit and effective-time/version history.

### 3.11 Controlled `PRICE_CHANGE` execution

- one internal `PRICE_CHANGE` business Command and platform-specific Adapter
  implementations; platform API symmetry is not assumed;
- Dry Run/Impact Preview with current value/version and projected guardrail facts;
- command idempotency and stale recommendation/entity-version refusal;
- Outbox and worker states that distinguish accepted, leased, executing,
  platform-pending, retry-wait, unknown-requires-readback, succeeded,
  readback-mismatch, compensated and final failure;
- timeout/unknown result triggers Readback/status enquiry before retry;
- final success requires platform-specific convergence plus Readback equality;
- restore/compensate path using captured prior value and current-state safety
  checks; no blind rollback over a later legitimate change;
- per-platform/account/store/SKU Capability flag, global Kill Switch and safe
  stop of new writes;
- real low-risk allowlisted Pilot Cohort verification on both platforms.

### 3.12 Structured UI

- public-login shell and role/scope-aware navigation;
- daily SKU priority queue;
- SKU Growth & Profit detail/360 view with cross-domain facts, Freshness,
  Confidence and evidence drill-through;
- mapping conflict and data-quality work queues;
- AI diagnosis/recommendation panel embedded in the structured view;
- Task/Approval/Policy/Price impact preview and command status/readback timeline;
- system integration/freshness/worker/command health surfaces;
- responsive, keyboard-usable, safe error states and Russian text/UTF-8 support;
- browser E2E for the complete operating path.

### 3.13 Operability and evidence

- structured logs, metrics, traces and alerts without Secret/PII/Raw leakage;
- runbooks for API outage, 429, Credential expiry/revoke, schema change, backlog,
  replay, mapping conflict, stale data, AI provider failure, unknown write result,
  readback mismatch, restore/compensate, Kill Switch and database/object restore;
- backup/PITR/object retention and actual restore drill before production release;
- contract, integration, replay, reconciliation, security, browser, performance
  and disaster-recovery evidence appropriate to the Slice;
- Requirement/Decision → Slice → Code → Test → Release/Evidence traceability.

## 4. Explicit non-goals

The Slice must not silently expand to:

- production Stock, Ads, Promotion, Listing-content or Order writes;
- a full Warehouse Workbench or complete Return QC product;
- statutory accounting, tax filing or ERP/WMS replacement;
- full financial close across all historical data;
- autonomous AI selection/execution of actions outside deterministic Policy;
- Buyer PII use in AI/general Analytics;
- a public multi-tenant SaaS, tenant registration or billing;
- microservices, Kafka, Kubernetes, multi-cloud or a second database authority;
- scraping, browser automation or unpublished Marketplace interfaces;
- unreviewed AI-authored Russian listing content publication;
- product-wide general availability on the first production release.

Other domains must support diagnosis and tasks in this Slice, but their real write
capabilities are promoted only by later independent Slice/Capability Contracts.

## 5. Source of truth and authority boundaries

| Concern | Sole authority |
| --- | --- |
| Human authentication | approved external OIDC IdP |
| Business authorization | MarketOps `identityaccess` application/domain service |
| Marketplace capability/credential metadata | `marketplaceintegration` |
| Secret material | approved Secret Manager, never MarketOps business tables |
| acquisition/job/cursor/replay/Raw coordination | `marketplaceintegration` Shared Spine |
| immutable Raw bytes | approved object storage + immutable DB custody metadata |
| Product/SKU/Listing identity | `productlisting` |
| canonical metrics/profit | versioned deterministic calculation services |
| AI inference/recommendation text | `aicopilot`, explicitly non-canonical |
| Commercial Policy/Guardrail | deterministic Policy service |
| Recommendation/Task/Approval | `operationsworkflow` |
| Price Command/Outbox | one governed command authority; Adapter only executes |
| Platform execution truth | native platform response/status + Readback evidence |
| Audit | append-only MarketOps audit path plus provider audit where applicable |

No Controller, UI component, AI model, Adapter or Repository may become a second
writer for another authority.

## 6. Binding invariants

1. No external call occurs without current account/endpoint/capability/credential,
   scope, feature-flag and authority validation.
2. Secret material is resolved only inside the approved Adapter boundary and
   never appears in request DTOs returned to business modules, DB rows, logs or UI.
3. Exact returned source bytes are durably stored and hash/readback verified before
   a related cursor/checkpoint is acknowledged.
4. Replay uses saved Raw and causes zero Marketplace acquisition calls.
5. Duplicate source/replay/import/command identity cannot create duplicate logical
   Core/Ledger/Task/Command effects.
6. Unknown source fields/states/results remain unknown and fail closed.
7. Unresolved SKU mapping or key cost conflict blocks precise profit and write.
8. Canonical metrics are deterministic, versioned and reproducible; AI cannot
   overwrite or silently recalculate them as official truth.
9. AI Fact claims require valid Metric/Evidence references. Model Confidence does
   not replace data quality or policy evidence.
10. Buyer PII and Secret fields cannot enter the AI projection or general Mart.
11. Price execution cannot start without current Recommendation/intent,
    deterministic Guardrails, approval or matching pre-authorized Policy,
    unexpired scope, current entity version and idempotency key.
12. Timeout/unknown platform result is never blindly resubmitted; Readback/status
    resolution precedes retry or manual resolution.
13. Platform success is not final success until required Readback converges.
14. A stale worker/fence/lease cannot advance cursor, command or terminal state.
15. Disabling the global/platform/account/store/capability flag prevents new
    external writes without corrupting in-flight evidence.
16. Restore/compensate cannot overwrite a later legitimate external change.
17. All user/Policy/Approval/Command/Readback/override/Kill-Switch events are
    attributable and append-only.
18. Production writes remain disabled after code merge until the separate
    Controlled Write Capability Gate and Human Owner enablement.
19. V0001–V0010 and existing evidence are immutable; schema evolution is V0011+.
20. One Slice implementation may extend the Shared Spine but cannot fork it.

## 7. Required state models

### Recommendation/workflow

```text
DRAFT
→ VALIDATED
→ READY_FOR_REVIEW
→ APPROVED | POLICY_AUTHORIZED | REJECTED | EXPIRED | CANCELLED
→ COMMAND_CREATED
→ EXECUTION_TRACKING
→ OUTCOME_OBSERVATION
→ CLOSED
```

A Recommendation can remain `TASK_ONLY` when its action type has no enabled write
Capability.

### Price command

```text
PENDING
→ LEASED
→ EXECUTING
→ PLATFORM_PENDING
→ READBACK_PENDING
→ SUCCEEDED

EXECUTING / PLATFORM_PENDING / READBACK_PENDING
→ RETRY_WAIT
→ UNKNOWN_REQUIRES_READBACK
→ READBACK_MISMATCH
→ MANUAL_RESOLUTION
→ FAILED_FINAL
→ COMPENSATION_PENDING
→ COMPENSATED | COMPENSATION_FAILED
```

The exact platform-native substates may differ and remain Adapter-specific.

### Data confidence

```text
CANONICAL_CONFIRMED
CANONICAL_PENDING_SETTLEMENT
ESTIMATED_EXPLAINED
STALE
INCOMPLETE
CONFLICTED
UNKNOWN
```

## 8. Production Acceptance Contract

The Slice is not complete until each applicable criterion has executable evidence.

### A. Identity, security and scope

- `S1-AC-001` — public OIDC login and mandatory MFA work in the approved
  environment; unauthenticated access is denied.
- `S1-AC-002` — MarketOps backend enforces Role + Store + Platform + Action Scope;
  horizontal/vertical privilege escalation tests fail closed.
- `S1-AC-003` — user disable/revoke and sensitive-action reauthentication/session
  behavior are verified and audited.
- `S1-AC-004` — no Secret, Buyer PII, signed object URL or unsafe Raw content
  appears in Git, browser bundle, log, trace, error or AI invocation.

### B. Infrastructure and recovery

- `S1-AC-005` — Yandex production topology is reproducible from reviewed IaC and
  uses least-privilege workload identities/roles.
- `S1-AC-006` — PostgreSQL PITR/backup and object-storage retention/integrity
  controls are configured and an actual restore drill meets the accepted target.
- `S1-AC-007` — monitoring, alerting and runbooks cover the critical Slice paths;
  failure injection proves operator-visible degradation rather than silent loss.

### C. Marketplace Capability evidence

- `S1-AC-008` — current official-source and real-account evidence proves required
  Ozon read capabilities and `PRICE_CHANGE` write/readback behavior.
- `S1-AC-009` — equivalent evidence exists for Wildberries without pretending its
  task/status/error model is identical to Ozon.
- `S1-AC-010` — quotas, pagination, freshness, error/timeout/unknown-result and
  Credential scope are recorded with last-verified date and contract tests.

### D. Ingestion and Raw

- `S1-AC-011` — scheduled/manual Slice acquisition is restartable, rate-limited,
  retry-budgeted and fenced on a real PostgreSQL path.
- `S1-AC-012` — success and business-meaningful failure bytes are immutable in the
  approved object store with exact hash/length/provenance and read verification.
- `S1-AC-013` — cursor cannot outrun committed verified Raw under crash/failure
  injection.
- `S1-AC-014` — duplicate/replay/backfill processing creates no duplicate logical
  effects; replay makes zero Marketplace acquisition calls.
- `S1-AC-015` — schema drift, unknown field/state and missing/orphan object paths
  are observable and recoverable.

### E. Product identity and internal intake

- `S1-AC-016` — pilot listings/variants map to Internal SKU or an explicit conflict
  queue with effective-time version history; unresolved mapping blocks write.
- `S1-AC-017` — COGS, physical stock and finance facts can be entered manually and
  imported through the productized Excel/CSV path with preview, hash, validation,
  rejection, audit and replay.
- `S1-AC-018` — duplicate, malformed, stale and conflicting imports are handled
  deterministically; no silent overwrite occurs.

### F. Facts, metrics and diagnosis

- `S1-AC-019` — key funnel, stock, return, ad and profit facts are traceable to
  source Raw and show Source Time/Freshness/Confidence.
- `S1-AC-020` — Contribution Profit/Minimum Price and canonical/estimated states
  reproduce against versioned golden examples; missing inputs do not produce fake
  precision.
- `S1-AC-021` — Completed/Retained/Settled Sale and late-return/adjustment behavior
  are tested without rewriting historical source facts.
- `S1-AC-022` — deterministic diagnosis and rule order correctly identify or
  decline Low Impression/CTR/Conversion, High Return, Stockout Risk, Negative
  Margin and Data Blocked cases.

### G. AI

- `S1-AC-023` — AI projection allowlist and PII/Secret negative tests pass.
- `S1-AC-024` — structured AI output distinguishes Fact/Inference/Recommendation/
  Unknown and rejects nonexistent Metric/Evidence references.
- `S1-AC-025` — model failure, timeout, malformed output and provider unavailability
  degrade safely; no deterministic Gate is bypassed.
- `S1-AC-026` — approved golden diagnostic cases demonstrate useful cross-domain
  reasoning while preserving explicit uncertainty and competing explanations.

### H. Workflow, policy and price execution

- `S1-AC-027` — Recommendation → Task → Approval/Policy Authorization is complete,
  scoped, expiring, attributable and immutable in audit.
- `S1-AC-028` — Commercial Policy versions and overrides apply deterministically;
  missing/invalid/expired policy denies execution.
- `S1-AC-029` — price Dry Run/Impact Preview uses current canonical facts, entity
  version and prior platform value; stale previews cannot execute.
- `S1-AC-030` — command idempotency, lease/fence, retry and state transitions pass
  unit, property and real-database tests.
- `S1-AC-031` — low-risk real Ozon Pilot write reaches the intended final value,
  Readback and complete Audit; unknown result is safely resolvable.
- `S1-AC-032` — equivalent real WB Pilot evidence exists, including native
  asynchronous/partial/quarantine semantics where applicable.
- `S1-AC-033` — Restore/Compensate is actually verified on both platforms without
  overwriting a later change.
- `S1-AC-034` — global and scoped Kill Switches prevent new writes; disabled flags
  are fail-closed under restart/concurrency.

### I. UI and operations

- `S1-AC-035` — browser E2E proves login → priority queue → SKU diagnosis →
  evidence → recommendation → approval/policy → price command → readback timeline.
- `S1-AC-036` — UI never labels stale/estimated/unknown/readback-mismatch state as
  confirmed success.
- `S1-AC-037` — common priority/SKU queries meet accepted performance targets on
  representative data; async export is used for large output.
- `S1-AC-038` — support personnel can recover representative API outage, backlog,
  replay, AI failure, unknown write and database/object restore scenarios using
  committed runbooks.

### J. Release

- `S1-AC-039` — all required CI/security/contract/integration/browser checks pass
  on the exact release Head; no unresolved Critical/High finding remains.
- `S1-AC-040` — the Pilot Cohort, approved users, Stores, Capabilities, Policy
  limits, monitoring window and rollback/kill criteria are explicitly recorded.
- `S1-AC-041` — merge, deployment and production enablement are distinct
  authorizations; the code ships with production writes disabled.

## 9. Required evidence classes

```text
SOURCE_REVIEW
UNIT_OR_PROPERTY_TEST
REAL_DATABASE
OBJECT_STORAGE_INTEGRATION
REAL_PROVIDER_OR_EXTERNAL_SYSTEM
SECURITY_NEGATIVE_TEST
BROWSER_E2E
PERFORMANCE
DISASTER_RECOVERY
OPERATOR_RUNBOOK_DRILL
AUDIT_TRACE
```

A fixture or mock may prove local logic but cannot substitute for a required real
Marketplace, Identity, Object Storage or deployment evidence class.

## 10. Implementation freedom delegated to Claude/Codex

Without a new synchronous Design Gate, the implementation agents may decide:

- package/class/component decomposition within accepted module boundaries;
- SQL/index/query shape and forward-only V0011+ migration details;
- internal API/DTO names and helper abstractions;
- libraries/SDK usage that does not introduce a new trust/deployment boundary;
- worker algorithm details that preserve the invariants;
- test fixture and suite organization;
- UI component composition and interaction details within the product contract;
- refactoring necessary to integrate preserved assets safely.

They must document material choices in the Slice design/as-built record and ADR
only when the choice has durable cross-Slice architectural consequences.

## 11. Conditional Pre-Implementation Design Gate triggers

Stop only when implementation requires one of the following and the Contract does
not already decide it:

- a new/replaced Source of Truth, second writer or authority;
- destructive/irreversible migration, historical data rewrite or unbounded
  backfill without a safe compatibility/rollback path;
- a new Secret/PII/cross-border trust boundary;
- a new external Provider, deployment topology, database or messaging platform;
- a product/financial/fulfillment meaning with multiple materially different
  Owner outcomes;
- platform write semantics whose unknown-result/readback/restore path cannot be
  made safe under the existing Contract;
- a security/control reduction or production data-loss/financial-loss risk not
  bounded by the current Acceptance Contract.

Normal detailed engineering discoveries are handled inside implementation and
reported at Deep Review.

## 12. Stop conditions during implementation

Return a precise blocker rather than guessing when:

- official platform documentation/account access cannot establish a required
  Capability fact;
- a real Credential/Secret or legal confirmation is required from the Owner;
- no approved redacted internal data sample exists for a mandatory importer;
- a selected external provider is unavailable/ineligible for the production
  business and no contract-compatible provider can be substituted;
- a required safe Readback/restore contract is impossible;
- continuing would require changing a fixed Owner Decision or hard invariant.

Implementation should continue on unaffected in-scope work whenever safe.

## 13. Git and PR execution boundary

The default is one Slice branch and focused Draft PR, but the implementation may
use bounded sequential tranches when one PR would become unreviewable or when
provider evidence must arrive later. Every tranche:

- cites `SLICE-V1-001` and this Contract;
- states which acceptance criteria it advances;
- leaves the repository buildable and migrations forward-only;
- does not claim Slice completion or product readiness;
- does not reintroduce a mandatory Design Gate;
- receives independent review according to its risk and merge impact.

## 14. Authorization after DR-0003 merge

```text
NEXT_AUTHORIZED_ACTOR: CLAUDE_FABLE_5
NEXT_ACTION: SLICE_V1_001_DETAILED_DESIGN_AND_INITIAL_FULL_IMPLEMENTATION
AUTHORIZATION: FULL_SCOPE_IMPLEMENTATION
PRODUCTION_WRITE_ENABLEMENT: PROHIBITED_PENDING_SEPARATE_GATE
```

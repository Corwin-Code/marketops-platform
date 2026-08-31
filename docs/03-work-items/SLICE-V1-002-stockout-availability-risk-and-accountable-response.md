# SLICE-V1-002 — Stockout & Availability Risk with Accountable Response

```yaml
document_type: production_delivery_slice_contract
contract_id: MARKETOPS-SLICE-V1-002
slice_id: SLICE-V1-002
product_version: V1
roadmap_title: Inventory & Availability Optimization
accepted_slice_title: Stockout & Availability Risk with Accountable Response

canonical_path: docs/03-work-items/SLICE-V1-002-stockout-availability-risk-and-accountable-response.md
status: DRAFT_AWAITING_EXACT_HUMAN_OWNER_ACCEPTANCE
source_protected_main: 8a7076877374391cf851481c023dfb0e621ab712
source_protected_main_tree: b87ec67d0242eb86e15698ab95430c37f0fe4328
source_protected_main_signature: VERIFIED_VALID
contract_date: 2026-08-31

owner: Human Owner
controller: GPT-5.6 Pro
maker: Claude Fable 5 / Claude Code
rework_agent: Codex

owner_decision_count: 20
delivery_risk: HIGH
primary_outcome:
  PREVENT_PROFITABLE_SKU_STOCKOUTS_AND_PRIORITIZE_ACCOUNTABLE_RESPONSE

implementation_authority_after_exact_acceptance:
  FULL_SCOPE_IMPLEMENTATION_WITHIN_EXECUTION_ENVELOPE_V1
conditional_design_gate_at_contract_gate: NOT_TRIGGERED

controlled_write_target: NONE_IN_THIS_SLICE
stock_change: DEFERRED_TO_SEPARATE_FUTURE_CAPABILITY_OR_RELEASE_CONTRACT
overstock_allocation_and_transfer: DEFERRED_TO_SEPARATE_FUTURE_SLICE_OR_CAPABILITY
production_enablement: DISABLED
real_external_integration: DEFERRED_TO_RELEASE_V1_001
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
production_write_enabled: false
```

## 1. Authority and acceptance semantics

This document is an exact Production Delivery Slice Contract proposal.

It becomes binding only when the Human Owner accepts all of:

```text
canonical path
exact bytes
SHA-256
source protected-main commit
source protected-main tree
```

Exact acceptance authorizes Claude to perform source understanding, evolvable
Detailed Design, Full-Scope Implementation, tests, isolated runtime evidence,
forward-only migrations, canonical documentation synchronization and local Git
checkpointing continuously inside `EXECUTION_ENVELOPE_V1`.

Acceptance does not authorize:

```text
real Ozon or Wildberries calls
real production Credentials
deployment
production database access
production migration execution
Gate EV
Gate E
Pilot
STOCK_CHANGE
any production write
```

The accepted original Contract is immutable. Normative changes require an exact
additive Amendment. Controller interpretation may only be non-expansive.

## 2. Observable business outcome

An authorized MarketOps user can open a structured Stockout & Availability queue
and, for every visible Internal Variant within their scope:

1. distinguish exact channel availability risk from company replenishment risk;
2. understand why the card is ranked and which evidence is Fresh, stale,
   incomplete, conflicted, estimated or carried forward;
3. see current owned supply, safely deduplicated Marketplace stock, eligible
   time-phased inbound, observed demand, lead-time/safety policy and profit lane;
4. receive one accountable, cause-routed Task when the governed lane requires
   action;
5. record attributable action evidence;
6. see MarketOps automatically verify whether the business risk improved;
7. have the same Case reopen or escalate when evidence expires, ETA slips or the
   risk returns;
8. record an authorized, evidence-backed, scoped and expiring accepted risk
   without falsely changing the calculated risk to `HEALTHY`.

The Slice succeeds when material profitable-stockout risks become timely,
explainable and accountable work with verifiable outcomes. It is not judged by
page count, raw alert count or Task-click completion rate.

## 3. Primary users and responsibility

| Responsibility | Primary role |
| --- | --- |
| Channel availability restoration | `MARKETPLACE_OPERATOR` / responsible Marketplace domain lead |
| Company replenishment review and inbound attestation | `PRODUCT_PROCUREMENT` |
| Lead-time and safety policy | `PRODUCT_PROCUREMENT` policy owner |
| Stock, Mapping, ownership and source defects | `TECH_DATA`, Mapping or accountable source owner |
| Profit/cost-data blocker | `FINANCE_ANALYST` / Cost Data owner |
| Return/quality blocker | Product, Supplier or Quality owner |
| HIGH exception approval | `OPS_LEAD` or effective-dated delegate |
| CRITICAL, repeated or material exception approval | Owner-designated Risk Authority |
| Read-only audit | `AUDITOR` |

All read, Task, policy, attestation and exception actions are constrained by
Organization and applicable Platform/Store/Product/Data/Action Scope. Frontend
visibility is not authorization.

## 4. End-to-end operating path

```text
authorized fact intake
→ immutable provenance and canonical fact acceptance
→ affected-Variant targeted recalculation
→ exact Channel risk + fail-closed Company risk
→ deterministic lanes and grouped Variant priority queue
→ lane/cause-based Task activation and deduplication
→ accountable action evidence
→ automatic outcome verification
→ verified success | continuing verification | same-case reopen/escalation
→ governed accepted exception when explicitly authorized
→ hourly full reconciliation and audit
```

AI may explain an already-calculated card in future product work, but AI is not
required by this Slice and cannot become a Metric, risk, policy, Task,
attestation, exception or authorization authority.

## 5. In scope

### 5.1 Grouped Internal Variant card

The parent card identity is:

```text
Organization + Internal Product Variant
```

It contains independently governed child risks.

Channel child identity:

```text
Platform
+ Marketplace Account/Store
+ Listing Variant
+ Fulfillment Mode
```

Company child identity:

```text
Organization
+ Internal Product Variant
```

Channel and company child states, evidence, Confidence, severity, Task cause and
clearance remain independent. The parent uses the most severe eligible child
lane, while always exposing the child that caused the rank.

### 5.2 Current company-owned supply

Company supply includes only units proven to be:

```text
company-owned
physically distinct
currently usable
safely deduplicated
within the applicable Organization/Variant authority
```

The calculation must account for applicable:

```text
reservation
QC lock
write-off/damage
sellability
stock type
ownership
```

A platform-visible FBS or seller-warehouse value that mirrors the same internal
warehouse stock is not additional supply.

Physically distinct company-owned FBO/platform stock may count only when its
ownership, Variant identity, current availability and duplication boundary are
attributable.

### 5.3 Time-phased confirmed inbound

Inbound is not current on-hand stock. It enters the projection only at its
eligible expected-arrival window.

An inbound record may reduce company risk only when a role-scoped authorized
Product/Procurement actor binds attributable evidence for:

```text
Organization
Internal Variant
quantity
arrival date or bounded window
business status
external order/shipment/procurement reference
evidence reference
source time
last verification time
accountable actor
```

A business-equivalent `DRAFT`, `REQUESTED` or buyer estimate is visible but does
not reduce risk.

Evidence-backed supplier-confirmed or in-transit status may reduce risk while
Fresh, non-conflicting and eligible.

Cancelled, overdue, stale, ambiguous, conflicting or unknown inbound immediately
ceases to provide safety and triggers recalculation.

Exact enum names and persistence design remain Detailed Design choices.

### 5.4 Lead-time and safety policy

Canonical lead time and safety days are a versioned, effective-dated,
evidence-linked Product/Procurement policy.

Deterministic resolution is equivalent to:

```text
1. Variant + Supplier + Route
2. Supplier or Product Category
3. Organization default
4. no valid policy → POLICY_BLOCKED
```

Each version binds:

```text
Owner
scope
lead-time value or bounded window
safety days
effective period
reason/evidence
last review time
version
fallback relation
```

Missing, stale, overlapping, conflicting or invalid policy cannot become zero
lead time, zero safety days or an implementation default.

Historical delivery performance may be evidence for review. It does not
automatically rewrite canonical policy in this Slice.

### 5.5 Observed demand policy

Canonical operational demand is deterministic and versioned over:

```text
D7
D14
D30
```

Each decision exposes:

```text
window values
sample sufficiency
Freshness
Confidence
selected rate
selection reason
policy/version
known gaps and outliers
```

The policy supports observable business-equivalent states such as:

```text
stable baseline
sustained recent acceleration
sustained recent deceleration
low sample
window conflict
outlier review
data blocked
```

It cannot silently choose the most urgent window, use an unversioned weighted
average, accept AI-created demand or hard-code an illustrative Socratic example.

### 5.6 Sale stage and return-quality Guardrail

Fresh attributable `COMPLETED` sales are the primary timely depletion signal.

`RETAINED` sales, returns, refusals, reasons and QC evidence are separate
demand-quality and Confidence Guardrails.

Required behavior is equivalent to:

```text
healthy retention + complete return evidence
→ ordinary eligible observed demand

material high return/refusal or defect pattern
→ quality review and/or governed priority downgrade

stale/incomplete/conflicted return evidence
→ demand-quality blocked/review
```

Demand and returned supply remain separate facts.

The system must not implement:

```text
Completed Sales - Returns = Demand
```

A returned unit becomes sellable supply only through an attributable inventory
Ledger fact proving re-entry after the applicable QC/state transition.

### 5.7 Availability-aware censored demand

A period with no opportunity to sell is not ordinary evidence of zero demand.

Every D7/D14/D30 window carries attributable observation coverage for:

```text
listing sellability
stock availability
source Freshness
known platform/data outage
channel or company scope
censoring
```

A materially censored window cannot lower canonical demand merely because its
Completed Sales are zero or small.

If another window is sufficiently observable, the deterministic multi-window
policy selects from eligible windows.

If all recent windows are materially censored, the last attributable eligible
demand/risk may be carried forward only for a bounded, versioned period with a
visible Confidence downgrade and source period.

When that period expires without restored observation:

```text
DEMAND_CENSORED / DATA_BLOCKED
```

Demand does not become zero. Lost demand is not imputed by a model.

### 5.8 Profit eligibility

The primary profitable-stockout queue uses the strongest applicable current
profit authority:

```text
Fresh + complete + positive Settled Contribution Profit
→ confirmed eligible

Settled unavailable for the operating horizon,
Fresh + complete + positive Operational Contribution Profit
→ operational eligible

positive only through explicit estimate
→ provisional / profit review

stale, incomplete, conflicted or unknown profit
→ profit data blocked

Fresh + complete zero or negative profit
→ not profitable for the primary queue / profit repair
```

Hero/Growth lifecycle may rank an already eligible item. It cannot silently
override a Fresh, complete negative-profit result.

A non-eligible stockout remains observable and is routed to an explicit
profit/data/quality path rather than disappearing.

### 5.9 Deterministic priority lanes

Canonical priority is deterministic and versioned.

Hard behavior:

```text
material data/freshness defect
→ review/data-blocked semantics

Owner-defined imminent-stockout emergency condition
→ CRITICAL and cannot be buried by a commercial score
```

Remaining eligible risks may be ordered using only visible, approved factors:

```text
time to stockout
Contribution Profit at risk
sales velocity
Owner-approved lifecycle
Confidence penalty
```

The UI shows every factor and policy/version rather than only an opaque score.

AI cannot calculate or override the canonical lane or order.

### 5.10 Partial-evidence and conflict semantics

Fresh exact channel facts remain independently actionable.

Example:

```text
Fresh Ozon FBO available = 0
→ Channel risk CRITICAL
```

A stale unrelated platform source does not erase that exact risk.

The Company child cannot be `HEALTHY`, `SAFE` or `VERIFIED_CLEAR` while a
material input required to establish company supply or demand is missing, stale,
incomplete, conflicted, unmapped or not safely deduplicated.

If known Fresh, safely deduplicated lower-bound evidence already proves danger,
the Company child may show:

```text
PROVISIONAL_RISK + DATA_BLOCKED
```

The result must disclose its conservative proof and cannot be represented as
fully confirmed.

If the conclusion depends on the missing/conflicting fact:

```text
COMPANY_RISK_UNRESOLVED / DATA_BLOCKED
```

Last-known stale stock, Mapping or ownership may be displayed with timestamps but
does not count as current supply after Freshness expiry.

### 5.11 Fact-triggered targeted recalculation

A qualifying accepted or invalidated fact triggers recalculation of the affected
Variant, child risks and active Case.

Trigger classes include:

```text
stock/sellability
Completed/Retained/Return evidence
inbound create/change/expiry/conflict/cancel
lead-time/safety policy
profit/lifecycle eligibility
Mapping/ownership correction
exception expiry/invalidation
Freshness loss/restoration
verification evidence
```

The implementation may choose its dependency index, queue, worker and transaction
mechanics. The product result is binding.

### 5.12 Hourly full reconciliation

The platform independently performs a full portfolio reconciliation at least
hourly.

For identical as-of authority, policy version and evidence set:

```text
targeted result = full-sweep result
```

The sweep catches or repairs:

```text
missed trigger
out-of-order or late fact
expired inbound/policy/exception/carry-forward
worker interruption
previously blocked fact that became resolvable
incorrect Task activation, verification or exception state
```

Repeated calculation is not a new Task identity.

### 5.13 Queue and structured UI

The primary structured UI includes:

- a Stockout & Availability priority queue;
- one grouped card per Internal Variant;
- exact Platform/Store/Listing/Fulfillment child details;
- company replenishment child details;
- lane, triggering child, rank factors and policy version;
- stock, demand, inbound, lead-time/safety and profit evidence;
- Freshness, Confidence, timestamps and blockers;
- current Task, assignee, due time, action/verification state and history;
- accepted-exception status, scope, approver and expiry;
- evidence drill-through and safe diagnostic errors;
- scope-aware filters and pagination;
- keyboard-usable and UTF-8/Russian-safe presentation.

The page never uses the same visual semantics for confirmed, provisional,
estimated, carried-forward, blocked and stale conclusions.

### 5.14 Task activation and deduplication

A versioned work-activation policy behaves equivalently to:

```text
CRITICAL
→ automatically create or update one accountable Task

sustained or policy-qualified HIGH
→ automatically create or update one accountable Task

WATCH
→ queue-visible; no automatic Task unless manually promoted or escalated

DATA_BLOCKED / POLICY_BLOCKED / QUALITY_REVIEW / PROFIT_REVIEW
→ cause-specific remediation Task when independently actionable
```

Routing follows the cause owner, not the viewer.

The same active cause updates one Case, appends evidence and changes severity,
due time or assignee only under governed policy.

Different independently actionable causes with different owners may create
separate Tasks.

A calculation-run identity is never the Case identity.

### 5.15 Two-stage action and outcome verification

Task completion distinguishes:

```text
Stage 1:
accountable structured action + attributable evidence

Stage 2:
fresh cause-specific outcome verification
```

A free-text acknowledgement is insufficient.

Action examples include eligible inbound evidence, channel-restoration reference,
data/mapping repair, effective policy version or quality disposition.

Verification examples include:

```text
company risk remains below activation threshold through the governed window
channel listing/mode is Fresh and sellable
data source and dependent calculation recover
one unique applicable policy resolves
quality disposition exists and eligibility is recomputed
```

Required outcomes:

```text
verified result
continued verification
automatic same-case reopen/escalation
failed/rework-required
```

Action SLA and Outcome SLA remain separate.

### 5.16 Accepted Exception

A calculated risk may receive an explicit business disposition without changing
its factual risk lane.

Every accepted exception is:

```text
evidence-backed
role-scoped
cause-scoped
business-scope-scoped
effective-dated
expiring
reviewable
auditable
automatically re-evaluated
```

It binds:

```text
risk/card/cause
Organization and exact scope
reason
rationale
expected commercial consequence
evidence
requester/decision owner
approver
effective period
review date
policy/version
```

The UI displays:

```text
calculated risk
+
ACCEPTED_EXCEPTION
+
expiry
```

It never relabels the risk `HEALTHY` or the outcome `VERIFIED_SUCCESS`.

Expiry, materiality increase, cause/scope change, evidence conflict, authority
loss, repeated exception or governing-policy change invalidates the exception
and reopens/escalates the same Case.

### 5.17 Exception approval escalation

Approval is proportional:

```text
bounded, non-repeated WATCH
→ responsible domain lead under explicit policy

HIGH
→ Operations Lead or effective-dated delegate

CRITICAL, repeated or material exposure
→ Owner-designated Risk Authority
```

For CRITICAL/repeated/material exceptions, the requester cannot be the sole final
approver.

Missing, expired, overlapping or conflicting approval authority produces:

```text
EXCEPTION_AUTHORITY_BLOCKED
```

The ordinary risk remains active.

Exact monetary, duration and recurrence thresholds are versioned operating
configuration. There is no permissive production default; absent valid
configuration fails closed.

## 6. Explicit non-goals and deferred work

The Slice must not implement or imply:

- replenishment quantity or latest-order-date recommendation;
- purchase requisition, supplier selection, cash approval or purchase execution;
- warehouse receiving/put-away or full WMS workflow;
- Dead Stock, Ageing, Slow-moving or Overstock diagnosis/Task loop;
- platform allocation recommendation;
- cross-warehouse or cross-platform transfer plan;
- clearance/exit optimization;
- advertising pause/limit recommendation or command;
- manual or generated target stock;
- stock-write Impact Preview, Approval, Command, Outbox, Adapter write or
  Readback;
- `STOCK_CHANGE`;
- predictive demand or lost-demand model;
- Owner-published demand plan;
- external AI invocation;
- real Ozon/WB call;
- real production OIDC, Yandex deployment, Credentials or production data;
- production enablement, Gate EV, Gate E or Pilot.

These remain durable future V1 obligations where the governing V1 Contract or
Baseline requires them. They are not deleted and may enter only through a
separate accepted Slice/Capability/Release Contract or exact Amendment.

## 7. Source of truth and authority matrix

| Concern | Sole authority |
| --- | --- |
| Organization/Store/Warehouse topology | `organizationaccount` |
| Human authentication | existing approved identity boundary; real production evidence deferred |
| Business role/scope | `identityaccess` + `operationsworkflow` |
| Internal Variant identity | `productlisting` Product Master/Mapping authority |
| Exact platform availability | attributable platform inventory fact for exact listing/mode |
| Internal physical/available stock | internal Inventory Ledger/snapshot authority |
| Company-stock ownership/deduplication | deterministic inventory ownership policy and evidence |
| Inbound eligibility | role-scoped evidence-backed Product/Procurement attestation |
| Demand | versioned deterministic D7/D14/D30 Completed-sales policy |
| Return quality | Retained/Return/QC evidence; not supply without Ledger re-entry |
| Lead time/safety | versioned Product/Procurement policy |
| Profit | existing deterministic Settled/Operational metric authority |
| Lifecycle strategy | Owner-approved effective-dated business policy |
| Priority | deterministic risk policy; AI explicitly non-authoritative |
| Recommendation/Task/Exception | `operationsworkflow` shared authority |
| Audit | append-only MarketOps audit authority |
| External Provider truth | deferred real read evidence; never guessed in code |

No UI, Adapter, AI model, Controller text or Repository may become a second writer
for another authority.

## 8. Binding invariants

1. Every query and mutation is Organization- and applicable business-scope
   constrained.
2. One Internal Variant parent cannot erase exact platform listing/mode identity.
3. Channel and Company child risk cannot silently clear one another.
4. Parent ranking always discloses the triggering child.
5. Internal and Marketplace-visible stock are not summed without proven physical
   distinctness and ownership.
6. Mirrored FBS/internal stock cannot be double-counted.
7. Inbound never becomes current on-hand before its eligible arrival time.
8. Stale, cancelled, overdue, conflicted or unknown inbound cannot provide safety.
9. Missing/invalid lead-time or safety policy fails closed.
10. Demand selection is deterministic, versioned and explainable.
11. Completed demand and returned supply remain separate facts.
12. Censored zero sales cannot prove low demand.
13. Carry-forward is bounded, attributable and visibly downgraded.
14. Profit estimates cannot masquerade as Settled fact.
15. Hero/Growth cannot silently override a confirmed negative-profit Gate.
16. An exact Fresh Channel stockout remains actionable despite unrelated source
    defects.
17. Material incomplete Company evidence cannot produce `HEALTHY`.
18. Provisional Company risk requires a reproducible conservative proof.
19. Missing decision-determinative evidence produces `DATA_BLOCKED`, not an
    invented lane.
20. Every canonical lane/rank binds policy version and evidence.
21. AI cannot calculate or override canonical risk.
22. CRITICAL cannot remain without an accountable Task unless a valid accepted
    exception governs the exact cause/scope.
23. Same active cause cannot create duplicate Tasks.
24. Action recorded is not verified success.
25. Outcome failure reopens/escalates the same Case.
26. Accepted risk is not resolved risk.
27. Exception authority absence fails closed.
28. Targeted and full-sweep results are equivalent for the same as-of evidence.
29. A missed targeted trigger is recovered by reconciliation.
30. Source latency and MarketOps internal latency are measured separately.
31. No Secret, Buyer PII or unredacted production payload enters Git, fixtures,
    logs, client bundles or general analytics.
32. No external provider call or platform write exists in the Slice execution
    path.
33. `production_write_enabled` remains `false`.
34. Applied migrations and historical evidence are immutable.
35. Every new schema change is forward-only and evidence-backed.
36. Shared ingestion, metric, policy, workflow and audit authorities are reused;
    no parallel stack is created.

## 9. Observable state semantics

Exact enum names may evolve, but the product must distinguish at least:

### Risk evidence

```text
CONFIRMED
OPERATIONAL
PROVISIONAL
CARRIED_FORWARD
DATA_BLOCKED
POLICY_BLOCKED
CONFLICTED
STALE
UNKNOWN
```

### Risk lanes

```text
HEALTHY
WATCH
HIGH
CRITICAL
REVIEW
UNRESOLVED
ACCEPTED_EXCEPTION
```

`ACCEPTED_EXCEPTION` is a disposition, not a replacement for the calculated lane.

### Task lifecycle

```text
OPEN / ASSIGNED / IN_PROGRESS
→ ACTION_RECORDED / VERIFYING
→ VERIFIED_SUCCESS
or
→ REOPENED / ESCALATED / REWORK_REQUIRED / ACCEPTED_RISK / CANCELLED
```

State-machine implementation is delegated, but no implementation may collapse
the required business distinctions into one generic success state.

## 10. Internal response SLO and operability

The SLO clock starts at `fact_accepted_at`.

### CRITICAL targeted path

```text
card and required Task create/update/escalate/reopen:
P95 <= 5 minutes

hard acceptance bound:
<= 15 minutes
```

### Other targeted paths

```text
HIGH / WATCH / data-policy-profit-quality blocker update:
hard acceptance bound <= 15 minutes
```

### Full reconciliation

```text
portfolio reconciliation:
at least once per 60 minutes

missed-trigger recovery:
by completion of the next scheduled successful hourly reconciliation
```

At the declared acceptance capacity profile, the full sweep must complete with
enough margin to sustain the hourly cadence. A missed or failed cadence becomes
an operator-visible SLO incident.

Evidence exposes:

```text
source_event_time
source_updated_at
ingested_at
fact_accepted_at
risk_calculated_at
task_activated_or_updated_at
reconciliation_started_at
reconciliation_completed_at
```

The engineering Slice proves internal SLO with deterministic fixtures and
isolated runtime. It does not claim real Marketplace end-to-end latency before
Release evidence.

Common UI query performance continues to honor the V1 NFR baseline; the Slice
must not trade traceability for query speed.

## 11. Compatibility, migration and recovery

- inspect the exact protected-main migration inventory before design;
- never edit an applied migration;
- use only new forward migrations when schema change is required;
- clean install and upgrade from protected `main` must both pass;
- migration failure must leave a diagnosable, recoverable state;
- derived risk projections are rebuildable from authoritative facts and policy
  versions;
- Task, exception and audit history is not silently rewritten during rebuild;
- worker restart/replay cannot create duplicate risk cases or evidence;
- late facts and policy changes trigger attributable recalculation;
- rollback means safe application rollback or forward-fix according to the
  migration class; destructive historical rewrite is prohibited.

## 12. Security, privacy and audit

- backend authorization is mandatory for every card, evidence, Task, policy,
  attestation, exception and export;
- users cannot read or mutate another Organization/Store/Platform scope;
- requester/approver and delegated authority are attributable;
- append-only audit covers policy, attestation, Task action, verification,
  reopen, exception request/approval/rejection/expiry and administrative replay;
- error and log payloads use safe identifiers and cannot leak Secret, Buyer PII
  or unsafe Raw content;
- synthetic or formally redacted data only in repository and test artifacts;
- no browser automation or unpublished Marketplace interface is used as a
  programmatic source.

## 13. Execution Envelope

### Level 1 — authorized after exact Contract acceptance

Claude may continuously:

- read source, Git history and canonical docs;
- create and evolve the Detailed Design;
- modify backend, frontend, tests, non-Secret config and canonical docs;
- create forward-only migrations;
- run build, lint, typecheck, unit/property/architecture tests;
- run isolated PostgreSQL integration tests;
- run local service/HTTP and browser E2E;
- use fake/mock/fixture providers and ephemeral sandboxes;
- run performance, concurrency, restart, reconciliation and failure-injection
  tests;
- create local Git branch, add, commit and exact checkpoints.

### Level 2 — not pre-authorized by this Contract

No shared non-production DB, real provider sandbox, real account, shared
integration environment or migration execution outside isolated/ephemeral
development is authorized.

An exact additive Amendment or dedicated authority is required if needed.

### Level 3 — separate authority

This Contract does not itself authorize remote Git publication, PR/Ready/merge,
production DB, production migration, deployment, real Credentials, destructive
operations or real business side effects.

The already-active Owner remote-publication delegation may be used by its named
delegate according to its own scope after an exact local checkpoint. This
Contract neither expands nor revokes that delegation. Protected merge still
requires the independent Controller verdicts and required repository Gates.

## 14. Stop conditions

Claude continues through difficult engineering problems while this Contract
remains satisfiable.

Stop and escalate only when evidence proves one of:

1. two binding Contract requirements cannot both be satisfied;
2. source truth disproves a core accepted business assumption;
3. implementation requires a second writer or Source of Truth;
4. implementation requires `STOCK_CHANGE`, allocation, replenishment quantity or
   another deferred product capability;
5. implementation requires real Provider access or a shared environment outside
   the accepted envelope;
6. implementation requires destructive/applied migration rewrite;
7. a new Secret, PII, legal or cross-border trust boundary is necessary;
8. the internal SLO requires a materially new deployment topology rather than an
   engineering optimization inside the accepted architecture;
9. a new Owner-level product/risk/cost/irreversible decision is unavoidable.

Transaction, locking, retry, index, caching, package decomposition, state-machine
implementation and ordinary refactoring are not pause reasons.

## 15. Required implementation artifacts

The implementation must deliver, in the same work:

- exact canonical Contract bytes and Owner acceptance provenance;
- `docs/02-architecture/designs/SLICE-V1-002-design.md`;
- backend and frontend implementation;
- forward migration(s) only when required;
- API/OpenAPI changes and generated client synchronization where applicable;
- unit, property, architecture, PostgreSQL integration and browser tests;
- deterministic Golden Dataset and mutation/adversarial tests;
- SLO/performance and reconciliation-recovery evidence;
- runbooks for backlog/SLO breach, stale source, mapping/ownership conflict,
  inbound expiry, policy blocker, Task verification and exception expiry;
- canonical `CURRENT_STATE`, roadmap, decision log and V1 traceability sync;
- `docs/07-phase-evidence/SLICE-V1-002/acceptance-status.md`;
- `docs/07-phase-evidence/SLICE-V1-002/executable-evidence.md`;
- machine-readable evidence inventories sufficient for Controller review;
- exact local Git commit/tree and clean-worktree handoff.

Docs are Definition of Done, not a separate documentation Gate.

## 16. Production Acceptance criteria

Every criterion below requires executable evidence or an explicitly listed
deferred Release obligation.

### A. Contract, scope and authority

- `S2-AC-001` — repository contains the exact accepted Contract bytes and
  attributable Human Owner acceptance evidence.
- `S2-AC-002` — canonical roadmap and current state reflect the narrowed
  Stockout/Availability queue-and-Task Scope while preserving original roadmap
  provenance and future V1 obligations.
- `S2-AC-003` — no Overstock, Allocation, Transfer, Advertising Intervention,
  replenishment-quantity or `STOCK_CHANGE` capability is implemented or claimed.
- `S2-AC-004` — Shared Spine authorities are reused; architecture tests prevent a
  parallel ingestion, metric, policy, workflow or audit authority.
- `S2-AC-005` — conditional Design Gate remains untriggered or any later trigger
  is handled through the exact governance path rather than silently bypassed.

### B. Identity, scope and responsibility

- `S2-AC-006` — backend Organization/Platform/Store/Product/Data/Action Scope
  enforcement prevents horizontal and vertical access escalation.
- `S2-AC-007` — channel, company, data, policy, profit and quality Tasks route to
  the correct accountable role under versioned policy.
- `S2-AC-008` — missing or conflicting assignee/approver authority fails closed
  and remains operator-visible.
- `S2-AC-009` — exception escalation enforces domain-lead, Ops Lead and
  Owner-designated Risk Authority boundaries, including requester separation for
  CRITICAL/repeated/material cases.
- `S2-AC-010` — all sensitive reads/mutations and delegation changes are
  attributable and audited.

### C. Risk identity and presentation

- `S2-AC-011` — one parent card exists per Organization + Internal Variant.
- `S2-AC-012` — channel child identity includes exact Platform, Store/Account,
  Listing Variant and Fulfillment Mode.
- `S2-AC-013` — company child is independently governed at Organization +
  Internal Variant.
- `S2-AC-014` — clearing one child cannot silently clear the other.
- `S2-AC-015` — parent lane and ordering expose the exact triggering child and
  policy version.
- `S2-AC-016` — confirmed, operational, provisional, blocked, stale and
  carried-forward evidence are visually and API-semantically distinct.

### D. Supply and inbound truth

- `S2-AC-017` — internal available supply correctly accounts for reservation, QC
  lock, damage/write-off and applicable sellability.
- `S2-AC-018` — FBS/seller-warehouse views that mirror internal stock are never
  double-counted.
- `S2-AC-019` — physically distinct company-owned platform stock counts only
  under attributable ownership and deduplication evidence.
- `S2-AC-020` — unknown or conflicting ownership prevents a company-safe result.
- `S2-AC-021` — inbound enters supply only at its eligible time window and never
  as current on-hand.
- `S2-AC-022` — only role-scoped, evidence-backed Product/Procurement attestation
  can make inbound eligible.
- `S2-AC-023` — draft/estimated, stale, cancelled, overdue, ambiguous or
  conflicting inbound cannot reduce risk.
- `S2-AC-024` — inbound amendment/expiry/cancellation creates attributable
  version history and triggers recalculation.

### E. Demand, return quality and censoring

- `S2-AC-025` — D7/D14/D30 values, sample sufficiency, selected rate, reason,
  Confidence and policy version are exposed.
- `S2-AC-026` — sustained recent acceleration changes the selected rate under
  transparent bounded policy; an isolated outlier cannot silently dominate.
- `S2-AC-027` — low sample, data gap, window conflict and unexplained outlier fail
  to explicit Review/Blocked states rather than zero.
- `S2-AC-028` — Completed Sales are the primary operational demand stage.
- `S2-AC-029` — Retained/Return/QC evidence acts as a distinct quality Guardrail.
- `S2-AC-030` — returned supply counts only after an attributable Inventory
  Ledger re-entry fact.
- `S2-AC-031` — materially unavailable periods do not count as ordinary zero
  demand.
- `S2-AC-032` — eligible-window selection remains deterministic and
  channel/company censoring remains distinguishable.
- `S2-AC-033` — bounded last-eligible demand carry-forward exposes source period,
  expiry and Confidence downgrade.
- `S2-AC-034` — expired carry-forward produces `DATA_BLOCKED`, never zero demand
  or indefinite historical demand.

### F. Lead time, profit and priority

- `S2-AC-035` — lead-time/safety policy resolves by exact scoped fallback and is
  effective-dated, versioned, evidence-linked and owned.
- `S2-AC-036` — absent, stale, overlapping or conflicting policy yields
  `POLICY_BLOCKED`.
- `S2-AC-037` — Fresh positive Settled Profit yields confirmed eligibility.
- `S2-AC-038` — Fresh complete positive Operational Profit may yield operational
  eligibility when Settled is unavailable.
- `S2-AC-039` — estimated profit is provisional; stale/incomplete/conflicted
  profit is blocked.
- `S2-AC-040` — Fresh complete zero/negative profit cannot enter the primary
  profitable-stockout queue through lifecycle override.
- `S2-AC-041` — hard imminent-stockout escalation cannot be buried by commercial
  scoring.
- `S2-AC-042` — normal rank uses only permitted visible factors and deterministic
  policy.
- `S2-AC-043` — AI or free-form text cannot change canonical lane or rank.

### G. Partial evidence and no false safety

- `S2-AC-044` — Fresh exact channel stockout remains actionable despite unrelated
  material source defects.
- `S2-AC-045` — material incomplete/conflicting company evidence can never
  produce `HEALTHY`, `SAFE` or verified clearance.
- `S2-AC-046` — provisional company risk is emitted only when a reproducible
  known-evidence conservative proof already establishes danger.
- `S2-AC-047` — if the missing fact determines the conclusion, company risk
  remains unresolved/data-blocked.
- `S2-AC-048` — stale stock, Mapping or ownership is context-only after expiry and
  cannot count as current supply.
- `S2-AC-049` — independent channel, data/Mapping and provisional commercial
  causes route to non-duplicated accountable Tasks.

### H. Task activation and lifecycle

- `S2-AC-050` — every unexcepted CRITICAL cause automatically creates or updates
  one accountable Task with due time and evidence.
- `S2-AC-051` — HIGH activates only under its sustained/hard governed condition
  and does so in the qualifying evaluation cycle.
- `S2-AC-052` — WATCH remains queue-visible without automatic Task noise.
- `S2-AC-053` — blocker/review cases create cause-specific remediation rather
  than misleading ordinary restock work.
- `S2-AC-054` — repeated recalculation updates one Case; concurrency and replay
  cannot duplicate it.
- `S2-AC-055` — independently actionable causes with different owners may have
  separate, explicitly related Tasks.
- `S2-AC-056` — free-text acknowledgement cannot satisfy the action stage.
- `S2-AC-057` — structured action evidence transitions to verification without
  claiming outcome success.
- `S2-AC-058` — fresh cause-specific outcome evidence is required for verified
  success.
- `S2-AC-059` — ETA/evidence/policy/source/risk regression automatically reopens
  or escalates the same Case with history preserved.
- `S2-AC-060` — Action SLA and Outcome SLA are separately observable.

### I. Accepted Exception

- `S2-AC-061` — exception preserves the calculated risk and uses an explicit
  accepted-risk disposition.
- `S2-AC-062` — exception requires exact scope/cause, evidence, rationale,
  commercial consequence, owner, approver, period, review and policy version.
- `S2-AC-063` — ordinary action SLA may pause only while exception governance and
  expiry remain active.
- `S2-AC-064` — expiry, materiality/cause/scope change, authority loss, evidence
  conflict or repeat condition invalidates the exception.
- `S2-AC-065` — invalidation reopens/escalates the same Case.
- `S2-AC-066` — no user can convert stale/conflicted evidence into a safe
  canonical result through an exception.
- `S2-AC-067` — no permanent hidden monitoring exclusion is introduced.

### J. Targeted recalculation, SLO and reconciliation

- `S2-AC-068` — every qualifying accepted/invalidated fact recalculates the exact
  affected Variant and causes.
- `S2-AC-069` — targeted and full-sweep result are identical for the same as-of
  evidence and policy.
- `S2-AC-070` — CRITICAL targeted card/Task update meets P95 <= 5 minutes and hard
  <= 15 minutes from `fact_accepted_at`.
- `S2-AC-071` — HIGH/WATCH/blocker targeted update meets hard <= 15 minutes.
- `S2-AC-072` — full portfolio reconciliation succeeds at least hourly at the
  declared acceptance capacity.
- `S2-AC-073` — a deliberately dropped targeted trigger is repaired by the next
  scheduled successful hourly reconciliation.
- `S2-AC-074` — missed sweep, backlog or SLO breach becomes an operator-visible
  incident.
- `S2-AC-075` — all source/internal timing fields required by this Contract are
  queryable and included in evidence.
- `S2-AC-076` — late, reordered and expired facts produce deterministic,
  attributable recalculation without duplicate Case effects.

### K. UI, API and operability

- `S2-AC-077` — structured queue, grouped card, evidence drill-through, Task and
  exception surfaces support the complete operating path.
- `S2-AC-078` — API filtering/pagination and frontend navigation inherit backend
  scope; platform DTO/SDK types do not leak into public business contracts.
- `S2-AC-079` — keyboard use, safe errors, UTC/internal time and Store-local
  display, UTF-8 and Russian text are verified.
- `S2-AC-080` — metrics/logs/traces cover targeted processing, sweep, backlog,
  dedup, verification, exception expiry and SLO.
- `S2-AC-081` — runbooks prove operator response to stale source, ownership
  conflict, policy blocker, backlog/SLO breach and failed reconciliation.

### L. Security, migration and no-write boundary

- `S2-AC-082` — Secret, Buyer PII, unsafe Raw and real Credentials are absent
  from Git, fixtures, logs, errors and client bundles.
- `S2-AC-083` — no real Provider call occurs in engineering tests or runtime
  evidence.
- `S2-AC-084` — no stock-write Preview, Approval, Command, Adapter write,
  Readback or hidden manual target path exists.
- `S2-AC-085` — `production_write_enabled` is and remains `false`.
- `S2-AC-086` — applied migrations remain byte-identical; only forward migrations
  are added when required.
- `S2-AC-087` — clean install and protected-main upgrade paths pass against real
  PostgreSQL.
- `S2-AC-088` — restart, replay, concurrency and reconciliation cannot duplicate
  facts, cards, Cases, actions, exceptions or audit events.
- `S2-AC-089` — append-only audit and historical policy/evidence versions survive
  rebuild and forward-fix.

### M. Test and evidence obligations

- `S2-AC-090` — unit/property tests cover calculations, policies and state
  invariants.
- `S2-AC-091` — architecture tests enforce Shared Spine, module and no-write
  boundaries.
- `S2-AC-092` — PostgreSQL integration tests cover concurrency, uniqueness,
  effective-time resolution, recalculation and migrations.
- `S2-AC-093` — browser E2E covers queue → Task → action → verification →
  reopen/exception under role scope.
- `S2-AC-094` — mutation/adversarial tests prove that removing a Gate,
  deduplication, expiry, scope or fail-closed condition causes test failure.
- `S2-AC-095` — performance evidence proves the internal SLOs and hourly sweep at
  the declared acceptance capacity.
- `S2-AC-096` — fault injection proves missed-trigger recovery, worker restart,
  late evidence and SLO incident visibility.
- `S2-AC-097` — Requirement/Owner Decision → Design → Code → Test → Evidence
  traceability is complete.
- `S2-AC-098` — full repository regression, governance validation, production-
  readiness validation and security scans pass with no threshold weakening.
- `S2-AC-099` — canonical docs, runbooks, evidence inventory and exact Git
  identity are synchronized in the same implementation.
- `S2-AC-100` — no unresolved BLOCKER or MAJOR implementation finding remains at
  Final Closure Verification.

## 17. Deferred Release obligations

The following are mandatory and production-blocking but do not block engineering
implementation or engineering closure when the corresponding deterministic
interfaces and fail-closed states are complete:

- `S2-REL-001` — real Ozon stock/sellability read capability, source semantics and
  actual account/store/FBO/FBS evidence;
- `S2-REL-002` — equivalent Wildberries stock/sales/freshness evidence without
  assuming platform symmetry;
- `S2-REL-003` — real internal warehouse/stock ownership, Mapping and
  deduplication evidence using approved redacted/controlled inputs;
- `S2-REL-004` — real Product/Procurement inbound attestation workflow and actual
  evidence classes;
- `S2-REL-005` — real Organization policy values for lead time, safety,
  demand/quality, work activation, exception materiality and delegation;
- `S2-REL-006` — real OIDC/MFA and production role/scope evidence;
- `S2-REL-007` — Yandex runtime, managed PostgreSQL, object custody,
  backup/restore, monitoring and legal/security release evidence;
- `S2-REL-008` — real-provider source Freshness and end-to-end latency evidence,
  explicitly separated from internal SLO;
- `S2-REL-009` — Key User/Operator acceptance and controlled operating evidence
  for the queue/Task loop;
- `S2-REL-010` — release traceability, runbooks, training and no unresolved
  release-blocking defect.

These obligations are consumed by `RELEASE-V1-001` or another separately accepted
release boundary after the Owner-declared V1 functional implementation milestone.

They do not authorize Gate EV, Gate E, Pilot, deployment or production write.

`STOCK_CHANGE`, overstock, allocation and transfer are future product
Capabilities, not deferred evidence rows for this Slice.

## 18. Engineering closure state

When `S2-AC-001` through `S2-AC-100` are executably verified and all
`S2-REL-*` rows remain explicitly recorded as production-blocking, the permitted
engineering closure claim is:

```text
CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS
```

It is not:

```text
PRODUCTION_READY
DEPLOYED
PILOT_ENABLED
GATE_EV_AUTHORIZED
GATE_E_AUTHORIZED
PRODUCTION_WRITE_ENABLED
```

## 19. Owner decisions incorporated

| ID | Accepted decision |
| --- | --- |
| OD-S2-001 | Prevent profitable SKU stockouts and prioritize replenishment response |
| OD-S2-002 | End at a prioritized Stockout Risk Queue |
| OD-S2-003 | Deterministic blended priority policy |
| OD-S2-004 | Grouped Variant card with separate Channel and Company risks |
| OD-S2-005 | Ownership-aware current stock plus high-confidence time-phased inbound |
| OD-S2-006 | Evidence-backed role-scoped Product/Procurement inbound attestation |
| OD-S2-007 | Tiered Settled → Operational profit Gate with Confidence lanes |
| OD-S2-008 | Versioned scoped Product/Procurement lead-time/safety policy with fallback |
| OD-S2-009 | Deterministic D7/D14/D30 observed-demand policy |
| OD-S2-010 | Completed Sales primary with Retained/Return quality Guardrail |
| OD-S2-011 | Availability-aware censored windows with bounded last-eligible carry-forward |
| OD-S2-012 | Lane-based, cause-routed, deduplicated automatic Tasking |
| OD-S2-013 | Two-stage action then outcome verification with same-case reopen |
| OD-S2-014 | Time-bounded evidence-backed role-scoped Accepted Exception |
| OD-S2-015 | Lane/duration/recurrence/materiality escalation to Owner-designated authority |
| OD-S2-016 | Defer `STOCK_CHANGE`; keep Queue/Task-only |
| OD-S2-017 | Defer Overstock/Slow-moving/Allocation and intervention workflows |
| OD-S2-018 | Fact-triggered targeted recalculation plus periodic full reconciliation |
| OD-S2-019 | Exact Channel continuity with asymmetric fail-closed Company risk |
| OD-S2-020 | CRITICAL P95 5m/hard 15m; other targeted hard 15m; hourly reconciliation |

## 20. Final Contract Gate consequence

After exact Human Owner acceptance:

```yaml
contract_gate_verdict: AUTHORIZED_FOR_FULL_SCOPE_IMPLEMENTATION
separate_preimplementation_design_gate: NOT_REQUIRED
next_actor: Claude Fable 5 / Claude Code
next_action:
  SOURCE_UNDERSTANDING
  + EVOLVABLE_DETAILED_DESIGN
  + FULL_SCOPE_IMPLEMENTATION
  + TESTS
  + ISOLATED_RUNTIME_EVIDENCE
  + CANONICAL_DOCS
  + EXACT_LOCAL_GIT_CHECKPOINT
```

Claude does not wait for intermediate Design, implementation, test or ordinary
Git-mechanics approval. It pauses only under the Contract stop conditions.

The Controller's next engineering decision point is the one-shot independent
Deep Review over the exact published implementation candidate.

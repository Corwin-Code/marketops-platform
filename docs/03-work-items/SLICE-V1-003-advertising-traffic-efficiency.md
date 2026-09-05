# SLICE-V1-003 — Advertising & Traffic Efficiency

```yaml
document_type: production_delivery_slice_contract
contract_id: MARKETOPS-SLICE-V1-003
slice_id: SLICE-V1-003
product_version: V1
roadmap_title: Advertising & Traffic Efficiency
accepted_slice_title: Advertising & Traffic Efficiency

canonical_path: docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md
status: DRAFT_AWAITING_EXACT_HUMAN_OWNER_ACCEPTANCE
source_protected_main: 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430
source_protected_main_tree: 0ca229112bcf351ab5c572dd8d375c647bab61c0
source_protected_main_parent: e0184852785f451256a36f52fa3d520ceea2c313
source_protected_main_signature: VERIFIED_VALID
contract_date: 2026-09-03

predecessor_slice: SLICE-V1-002
predecessor_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS
predecessor_snapshot:
  path: docs/07-phase-evidence/SLICE-V1-002/CLOSURE-SNAPSHOT-DRAFT.md
  git_blob_sha1: da35a11b30843603c5defdc10299bcf8b53fbc83
  sha256: f4847d4fdca8bede97decc02a12f99b2358b196d3d5b31a3aac60362ae41799f
predecessor_snapshot_acceptance:
  path: docs/08-handoffs/OWNER-SLICE-V1-002-CLOSURE-SNAPSHOT-ACCEPTANCE-EVIDENCE.md
  git_blob_sha1: 658458e0421ecf41bdbf5bba1c466c2ec69f571b
  sha256: 410d56fcba47ca2ccdd2807b743863e420a3ee49dea34cd3b60c1b71446f8be6
  owner_statement_sha256: ed01ebaac4e92ffc74e02bf9cecd3aafdb8c094305b53a3b66bca0764275763d

owner: Human Owner
controller: GPT-5.6 Pro
maker: Claude Fable 5 / Claude Code
rework_agent: Codex

owner_decision_count: 47
delivery_risk: CRITICAL
primary_outcome:
  IMPROVE_ADVERTISING_CONTRIBUTION_PROFIT_EFFICIENCY_WHILE_PRESERVING_CURRENT_SALES_VOLUME
roadmap_user_outcome:
  CAMPAIGN_AND_TARGET_EFFICIENCY_TIED_TO_INVENTORY_CONVERSION_AND_CONTRIBUTION_PROFIT

implementation_authority_after_exact_acceptance:
  FULL_SCOPE_IMPLEMENTATION_WITHIN_EXECUTION_ENVELOPE_V1
conditional_design_gate_at_contract_gate: NOT_TRIGGERED

dual_platform_business_scope:
  - OZON
  - WILDBERRIES
governed_manual_shadow: REQUIRED_ON_BOTH_PLATFORMS
controlled_write_target: AD_BID_CHANGE
controlled_write_provider_paths:
  evidence_gated_independently_per_platform_account_store_and_capability: true
ordinary_nonzero_impact_envelope_initial: ZERO
every_initial_nonzero_ad_bid_change: MATERIAL_IMPACT
standing_policy_automation: NOT_AUTHORIZED

engineering_closure_claim:
  CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS
real_external_integration: DEFERRED_TO_RELEASE_V1_001_AND_EXACT_CAPABILITY_GATES
release_v1_001: RESERVED_NOT_ACTIVATED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
deployment: NOT_AUTHORIZED
production_write_enabled: false
```
## 1. Authority and acceptance semantics

This document is an exact Production Delivery Slice Contract proposal.

It becomes binding only when the Human Owner accepts all of:

```text
canonical path
exact UTF-8 bytes with LF line endings and no BOM
SHA-256
source protected-main commit
source protected-main tree
```

Exact acceptance authorizes Claude to perform source understanding, evolvable
Detailed Design, Full-Scope Implementation, tests, isolated runtime evidence,
forward-only migrations, canonical documentation synchronization and local Git
checkpointing continuously inside `EXECUTION_ENVELOPE_V1`.

Exact acceptance does not authorize:

```text
real Ozon or Wildberries calls
real Marketplace Credentials
real OIDC or Yandex production activation
shared non-production or production environment mutation
deployment
production database access
production migration execution
Gate EV
Gate E
Pilot
provider-side AD_BID_CHANGE
any production write
remote Git publication except under a separate active delegation
```

The accepted original Contract is immutable. Normative changes require a
separately identified, exact, additive Human Owner-accepted Amendment. The
Controller may issue only non-expansive interpretations that do not change
product behavior, authority, acceptance, trust boundary, cost or execution
scope.

Conversation prose and Socratic examples are discovery evidence, not a parallel
normative source. The exact accepted bytes of this Contract become the sole
SLICE-V1-003 product acceptance authority, together with any later exact accepted
additive Amendment.
## 2. Source and predecessor anchoring

The Contract is anchored to protected `main` commit
`08ad7da7d9e75b4ddd1c387a22ac0affba9e1430`, tree
`0ca229112bcf351ab5c572dd8d375c647bab61c0`, with verified GitHub signature.

The accepted SLICE-V1-002 Closure Snapshot and its separate Human Owner
acceptance evidence are the predecessor state. Their exact bytes, hashes,
engineering closure, deferred Release obligations and `production_write_enabled
= false` state remain untouched.

SLICE-V1-003 may consume SLICE-V1-002 availability, sellability, mapping, demand,
profit, Task, Accepted Exception, targeted-recalculation and hourly-reconciliation
capabilities. It must not silently reopen, implement or imply:

```text
STOCK_CHANGE
replenishment quantity or order date
Overstock / Slow-moving / Dead Stock
Allocation / Transfer
production Provider authority from SLICE-V1-002
```

The first bounded governance recording after exact acceptance may synchronize
canonical state to:

```text
active_delivery_slice: SLICE-V1-003
active_gate: SLICE_V1_003_FULL_SCOPE_IMPLEMENTATION
authorization: FULL_SCOPE_IMPLEMENTATION
```

That synchronization must preserve every accepted SLICE-V1-002 path, byte,
Git identity and SHA-256 and must not activate Release, Gate EV, Gate E, Pilot,
deployment or production write.

### 2.1 Normative repository references

| Concern | Exact protected-main reference |
| --- | --- |
| V1 product contract | `docs/01-requirements/V1_PRODUCT_CONTRACT.md`; Git blob SHA-1 `b3004b21f325f52bc3ab48575065f52256f5b5c5` |
| V1 delivery roadmap | `docs/03-work-items/V1_DELIVERY_SLICES.md`; Git blob SHA-1 `4614c4d82b1ac2ab330ab4bde40aafd8f989cf49` |
| V1 Shared Spine | `docs/02-architecture/V1_SHARED_SPINE.md`; Git blob SHA-1 `cb20fd8126f42005a15f338156c1e8a080708200` |
| Product/development Baseline | `docs/01-requirements/baseline-v1.0-cn.md`; Git blob SHA-1 `d3c1789a4fc3b93188203b10ffbb95ef2abeafb2` |
| Predecessor Contract | `docs/03-work-items/SLICE-V1-002-stockout-availability-risk-and-accountable-response.md`; Git blob SHA-1 `1caa50f1b33011f7d226c83654835401c00bde1e` |
| Existing first controlled-write Contract | `docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md`; Git blob SHA-1 `efe7055d3184cd109bdacba45d7159eebba8a51b` |
| Current repository state | `docs/00-governance/CURRENT_STATE.md`; Git blob SHA-1 `3c0fd9a644801ee35005fbc58035bbc56621ae2a` |
| Contract-governed development | `docs/02-architecture/adr/ADR-0006-contract-governed-vibe-coding.md`; Git blob SHA-1 `f33175bcde3888732aa013c2cb0bcff5c0d92e25` |

Where a reference describes high-volatility Provider facts, current official and
account-level evidence remains required at the consuming Release or Capability
Gate. This Contract does not convert an illustrative Baseline endpoint, timing or
threshold into a current production fact.
## 3. Observable business outcome

An authorized MarketOps user can open a structured Advertising Control surface
and, for every visible atomic Advertising Case within their scope:

1. identify the exact Ozon or Wildberries native advertising control object and
   the complete affected Internal Variant set;
2. distinguish official traffic/spend and provider-native attribution from
   canonical company sales, deterministic ad-linked conversion and Contribution
   Profit;
3. see which facts are Fresh, complete, sample-sufficient, estimated, stale,
   conflicted, blocked or unavailable for each decision purpose;
4. understand whether the Case is `PROTECTION`, `DATA_REPAIR`,
   `OPTIMIZATION` or `WATCH`, why it is ranked and who owns the next action;
5. obtain a deterministic bounded recommendation, accountable Task, governed
   Manual Execution Packet or exact `AD_BID_CHANGE` Preview where eligible;
6. complete Maker-Checker approval, provider or manual configuration
   verification, Readback, exception, quarantine, compensation and Kill-Switch
   workflows without creating a second execution authority;
7. see early Completed-Sales safety results, 30-day Retained-Sales protection,
   Operational advertising-profit results and later Settled confirmation;
8. have the same Case or Outcome lineage reopen or escalate when evidence,
   policy, provider state or business results regress;
9. use a Live Queue, Daily Action Brief and Weekly Evidence Review that all
   project the same Canonical Truth.

The Slice's primary success outcome is:

```text
complete affected-set company 30-day Retained Sales passes
AND every frozen required critical sales unit passes
AND at least one of:
    absolute Advertising Contribution Profit
    Contribution Profit per official advertising RUB
    materially improves
AND the other profit axis is not materially worse
AND the applicable baseline, evidence, policy and confounder Gates pass
```

A material reduction of a still-negative profit result is
`IMPROVED_NOT_HEALTHY`, not verified healthy success. Cause-verified protection
outcomes may be completed and reported separately, but cannot be counted as the
primary efficiency success when sales or dual-axis profit conditions are not met.

The Slice is not judged by page count, alert count, raw Recommendation volume,
provider HTTP success or configuration Readback alone.
## 4. Primary users and responsibility

| Responsibility | Primary authority |
| --- | --- |
| Advertising operating decision and accountable closure | `OPS_LEAD` within exact Organization/Platform/Store scope |
| Case review, candidate selection and action making | scoped `MARKETPLACE_OPERATOR` |
| Material per-command operating endorsement | `OPS_LEAD` distinct from the Maker |
| Initial material per-command final approval | `OWNER` |
| Later evidence-promoted Ordinary per-command final approval | distinct `OPS_LEAD` under exact Owner-approved promotion policy |
| Company sales, cost, fee and profit truth | canonical sales/finance/metric authorities; Finance review where applicable |
| Product identity and complete affected-set | `productlisting` Mapping/Product authority |
| Availability and sellability truth | inherited applicable product/listing/inventory authorities |
| Provider capability, credential reference, invocation and Readback | `marketplaceintegration` |
| Recommendation, Task, Approval, Exception and commercial policy | `operationsworkflow` |
| Metric definitions, calculation versions and canonical baselines | `analyticsdecision` |
| Platform/credential/security integrity Kill | scoped `PLATFORM_ADMIN` / Security authority |
| Highest risk, delegation, Gate and reenablement approval | Human Owner |
| Read-only evidence and audit | `AUDITOR` within authorized scope |

The `OPS_LEAD` is the primary business decision owner for the operating loop.
The Marketplace Operator remains the scoped Maker and platform-action specialist;
Finance provides or verifies financial truth; the Owner bears material write,
delegation, Gate and reenablement risk.

Read, propose, endorse, approve, execute, verify, disclose, kill and reenable are
separate permissions. Frontend visibility is not authorization. Task assignment
does not grant additional data access.
## 5. End-to-end operating path

```text
authorized official and internal fact intake
→ immutable Raw/provenance and source reconciliation
→ platform-native object lineage + complete affected-set resolution
→ canonical traffic, spend, sales, conversion and profit calculations
→ purpose-specific Freshness and evidence qualification
→ deterministic Protection / Data Repair / Optimization / Watch lane
→ non-compensating canonical priority
→ one cause-routed accountable Task where required
→ governed Manual Shadow or deterministic exact Bid candidate
→ Impact Preview and current Policy Bundle resolution
→ Maker selection + Operations endorsement + Owner/Ordinary approval
→ Approval Lease and pre-transmission live revalidation
→ idempotent Command/Outbox or governed manual execution
→ provider/manual configuration verification and exact Readback
→ early Completed-Sales safety observation
→ cause-verified protection result and/or 30-day Operational outcome
→ later Settled confirmation or same-lineage Regression
→ quarantine, Kill, exact Compensation or reenablement where applicable
→ Live Queue + Daily Action Brief + Weekly Evidence Review
→ targeted recalculation and hourly full reconciliation
```

AI may draft explanations and management-review narrative from an authorized
projection. AI cannot become the Metric, lane, priority, policy, candidate,
approval, Command, Provider, Readback, exception, quarantine, Kill, outcome or
Gate authority.
## 6. In scope

### 6.1 Dual-platform operating capability

Ozon and Wildberries are both first-class business platforms for this Slice.

Both platforms must support, to the depth permitted by current evidence:

```text
native advertising object and lineage observation
Campaign / target / keyword / SKU relationship
official spend, traffic and available attribution facts
inventory, sellability, conversion and profit diagnosis
Protection / Data Repair / Optimization / Watch queue
accountable Task and Accepted Exception
governed Manual Shadow
configuration verification
Outcome Evaluation Plan
Live / Daily / Weekly operating surfaces
```

A shared `AD_BID_CHANGE` business Capability is implemented to production
quality, but a Provider invocation path is reachable only for an exact
Platform/Account/Store/Capability scope whose official and account-level evidence
is verified and whose Release, Gate EV or Gate E authority is active.

An unverified Provider path must be structurally unreachable and expose a clear
`UNVERIFIED` or Shadow-only state. Ozon evidence never authorizes Wildberries,
and Wildberries evidence never authorizes Ozon.

### 6.2 Atomic Advertising Case identity

The atomic Case is bound to:

```text
Organization
Platform
Marketplace Account / Store
minimum verified independently controllable native advertising object
native object kind and lineage
native bidding/control mode
complete affected Internal Variant set
affected-set digest/version
native Semantic Profile version
independent business cause
```

The minimum controllable native object may be Campaign, ad group, Target,
Keyword or another officially verified object. The product must not invent a
more granular write object than the Provider actually controls.

A native object affecting multiple Variants remains one atomic control Case with
the complete affected-set. SKU-level diagnostics may be shown, but an estimated
SKU allocation cannot create a separate executable Case or independently write
part of an indivisible native object.

A rebuilt, remapped or mode-changed native object is a new current identity state
and invalidates old executable decision assets unless an exact versioned lineage
rule proves continuity.

### 6.3 Common business envelope and platform-native semantics

The product uses one common business Case envelope and stable internal
Capability names, while preserving platform-native meaning through versioned
Semantic and Capability Profiles.

A Profile must be able to express:

```text
native identifiers and object hierarchy
control level
bidding mode
field meaning
currency, unit, precision and valid step
min/max and validation rules
status and transition semantics
provider response and error classes
idempotency or explicit-not-applied semantics
propagation and Readback behavior
quota, permission and credential scope
source maturity and correction behavior
last verified evidence and owner
```

Ozon and Wildberries are not forced into false endpoint, object, state, timing or
write symmetry. Unknown native states remain unknown and fail closed for the
affected purpose.

### 6.4 Official traffic, spend and provider attribution

Official Marketplace API/report facts are the programmatic authority for native
advertising:

```text
Campaign/object configuration observation
official advertising Spend
Impression / view / Click or native traffic denominator
provider-attributed Order / Revenue / Conversion observation
native reporting window and correction history
```

Provider-native attribution remains a Provider observation. It may support
reconciliation, trend diagnosis, discrepancy work and governed Shadow, but does
not by itself become canonical company sales, deterministic ad-linked conversion
or final Contribution Profit truth.

Official Spend corrections are append-only facts that trigger attributable
recalculation. A missing metric is `NOT_AVAILABLE`, never zero.

Estimated SKU Spend allocation may be visible for diagnosis only when its method,
coverage, assumptions and Confidence are explicit. It cannot independently
support a high-risk Recommendation, `AD_BID_CHANGE`, settled result or exact
affected-SKU profit claim.

### 6.5 Company sales and sales-protection stages

Company qualified sales are independent of Provider attribution.

The operating sales stages remain distinct:

```text
Order
Completed Sale
Retained Sale
Settled Sale
```

Fresh attributable Completed Sales provide the early post-action safety
Guardrail. The primary final sales-protection stage is the versioned 30-day
Retained Sale observation.

Sales preservation requires:

```text
complete affected-set company total passes
AND
every action-time frozen required critical sales unit passes
```

Critical sales units are determined before action by the applicable versioned
Outcome Policy and may include:

```text
a protected Hero Variant
a policy-qualified high-exposure Variant
a required Variant + Platform/Store sales channel
```

Growth in another Variant or channel cannot offset failure of a required critical
unit. Noncritical units remain included in the company total and remain visible,
but do not automatically possess an independent veto.

Missing evidence for a required critical unit produces an unresolved or
inconclusive result; it cannot be replaced by growth elsewhere.

### 6.6 Advertising Contribution Profit and dual-axis efficiency

Canonical advertising economics use the existing deterministic Metric authority.

At minimum the Slice calculates and explains, at the complete atomic Case scope:

```text
official advertising Spend
attributable Net Sales under the accepted definition
COGS
Marketplace commission and variable fees
fulfillment / delivery / storage
return, refusal and refurbishment loss
promotion cost where applicable
variable tax estimate where applicable
other accepted variable fees
Advertising Contribution Profit
Contribution Profit per official advertising RUB
```

Canonical and Estimated inputs and results remain visually and API-semantically
distinct.

Primary efficiency evaluation is a non-compensating Pareto rule:

```text
at least one profit axis materially improves
AND
the other profit axis is not materially worse
AND
sales preservation passes
```

A still-negative final profit result is never `HEALTHY`. A large loss reduction
may be `IMPROVED_NOT_HEALTHY` and may support a separately verified protection
result while continuing the relevant risk responsibility.

### 6.7 Deterministic ad-linked conversion, Allowable CPA and Max CPC

The versioned `ADVERTISING_CONVERSION_DEFINITION` distinguishes:

```text
PROVIDER_NATIVE_CONVERSION_OBSERVATION
CANONICAL_AD_LINKED_ORDER_CONVERSION
CANONICAL_AD_LINKED_COMPLETED_SALE_CONVERSION
CANONICAL_AD_LINKED_RETAINED_SALE_CONVERSION
```

Write-grade canonical conversion requires:

```text
official eligible traffic denominator
deterministic linkage to the same atomic advertising object or governed scope
complete affected-set
same Platform/Store/native lineage and observation window
sufficient mapping and linkage coverage
Freshness
sample sufficiency
explicit sale stage and Metric Definition version
```

Buyer name, phone or address is not introduced as a linkage mechanism.

Company total sales cannot be divided by advertising Clicks to fabricate object
conversion. Material Provider-to-canonical attribution gaps remain visible and
fail closed for write-grade Max CPC.

The economic unit of Allowable CPA must exactly match the conversion numerator:

```text
per ad-linked Order
per ad-linked Completed Sale
or
per ad-linked Retained Sale
```

Order-stage Allowable CPA cannot be multiplied by Retained-stage conversion, and
the same cancellation/refusal/return loss cannot be counted twice.

`Max CPC = stage-consistent Allowable CPA × stage-consistent conversion rate`.

Write-grade Max CPC is an economic ceiling, not the automatic next Bid.

### 6.8 Purpose-specific Freshness and evidence qualification

Freshness is governed by a versioned:

```text
Evidence Kind
× Platform/Account/Store/Semantic Profile scope
× Decision Purpose
```

Profile.

Decision purposes include at least:

```text
QUEUE_OBSERVATION
TASK_ACTIVATION
PROTECTION_RECOMMENDATION
OPTIMIZATION_RECOMMENDATION
PROTECTION_BID_WRITE
OPTIMIZATION_BID_WRITE
EXACT_COMPENSATION
EARLY_COMPLETED_SALES_OUTCOME
FINAL_RETAINED_SALES_OUTCOME
SETTLED_FINANCIAL_OUTCOME
```

Freshness may depend on source update age, accepted-fact age, report/window
completeness, expected publication lag, known correction window,
effective-version validity, coverage, Confidence and Provider incident state.

A newly ingested old source fact is not automatically Fresh. A mature 30-day
cohort or still-effective Cost Version is not automatically stale merely because
its business period is old.

Write-grade requirements cannot be weaker than the corresponding Recommendation
requirements. Optimization Increase cannot use a weaker evidence class than a
Protection Task. Exact Compensation does not weaken current hard-safety
Freshness.

A missing, expired, conflicting or unverified Profile blocks only the purposes
that consume it. It cannot erase an independent Fresh danger fact.

Optimization qualification is separately governed by a versioned, scoped and
purpose-tiered Evidence Profile covering:

```text
eligible observation window
source and affected-set coverage
traffic denominator
Completed/Retained Sales evidence
Spend/exposure relevance
sustained eligible periods
sample sufficiency
material recoverable value
correction-window maturity
baseline/confounder eligibility
Confidence and boundary semantics
```

The monotonic purpose order is:

```text
WATCH
≤ OPTIMIZATION_TASK
≤ OPTIMIZATION_RECOMMENDATION
≤ OPTIMIZATION_BID_WRITE
```

An immature, unsustained or immaterial signal remains a reason-coded `WATCH`.
A material data defect is `DATA_REPAIR`, not a quiet Watch.

### 6.9 Deterministic lanes

Every atomic Case resolves to one calculated lane:

```text
PROTECTION
DATA_REPAIR
OPTIMIZATION
WATCH
```

Business-equivalent behavior is:

```text
Fresh one-sided proven danger
→ PROTECTION

decision-determinative missing, stale, conflicted or incomplete evidence
without a proven direction
→ DATA_REPAIR

complete, Fresh, sample-sufficient, sustained and material opportunity
with no hard block
→ OPTIMIZATION

visible but immature, unsustained or immaterial signal
→ WATCH
```

Unknown alone is neither safe nor proven danger. It cannot create a decrease,
increase, pause or success.

Increase requires complete write-grade safety evidence. Protection may remain
actionable from a Fresh one-sided danger proof when the missing fact cannot
reverse the dangerous direction. This directional asymmetry must be explicit and
reason-coded.

### 6.10 Canonical priority

The first ordering authority is the lane. Within a lane, Canonical Priority is a
versioned, non-compensating, reason-coded lexicographic policy.

Protection hard sub-tiers are:

```text
P0 — action/outcome Regression, active Quarantine, unresolved execution
     integrity or Compensation decision
P1 — confirmed sellability/availability danger or protected critical-sales risk
P2 — proven continuing advertising economic harm
P3 — other explicitly policy-qualified Protection danger
```

Within a Protection sub-tier, the order is:

```text
SLO breach or earliest valid due time
confirmed Contribution Profit loss rate
frozen critical-sales-unit exposure
official advertising Spend exposure
Case age
stable canonical Case identity
```

Data Repair ordering prioritizes blocked Protection/Quarantine/Compensation,
downstream blast radius, continuing Spend, blocked Outcome/Approval/Release,
SLO/age and stable identity.

Optimization ordering applies only after qualification and uses deterministic
recoverable Contribution Profit, dual-axis gap, Spend, critical-sales headroom,
evidence maturity, Task age and stable identity.

Watch ordering affects visibility only and creates no automatic action authority.

A weighted score may be shown for analysis, but cannot replace Canonical
non-compensating order. AI cannot calculate or override the Canonical lane or
rank.

### 6.11 Accountable Task and human response

The same active independent cause updates one Case and one active Task. A
calculation run is never the Case identity.

Task activation is equivalent to:

```text
PROTECTION
→ create or update one accountable protection Task

material independently actionable DATA_REPAIR
→ create or update one cause-owner remediation Task

qualified OPTIMIZATION
→ create or update one optimization Task

WATCH
→ queue-visible, no automatic Task
```

Different independently actionable causes with different owners may have
separate, explicitly related Tasks. Routing follows the cause owner, not the
viewer.

Human response uses a versioned, lane-specific, two-stage, coverage-aware Profile:

```text
Stage 1 — ACKNOWLEDGEMENT
Stage 2 — ACTION_DECISION_OR_FIRST_ATTRIBUTABLE_ACTION
```

Opening a page is not acknowledgement. Acknowledgement is not action. Action is
not Outcome.

Protection and Regression cannot have a weaker response Profile than
Optimization. Material Data Repair has a cause-owner Profile. Watch consumes no
automatic Action SLO.

Outside configured staffed coverage, the staffed Action clock may pause, but:

```text
OUT_OF_COVERAGE_ACTIVE_HARM
wall-clock exposure age
continuing official Spend
known profit/sales/availability exposure
next staffed response time
```

remain visible. No 24×7 coverage is presumed without an explicit staffed Profile.

Reassignment never resets Case age. SLO breach escalates only; it never authorizes
an ad write.

### 6.12 Exclusive governed Accepted Exception

A calculated Protection or material Data Repair Case may receive an explicit,
time-bounded risk disposition while preserving its factual lane.

The active operating dispositions are mutually exclusive:

```text
ACTION_REQUIRED
ACTION_IN_PROGRESS
ACCEPTED_EXCEPTION_ACTIVE
```

An Accepted Exception binds exact:

```text
Case/cause
Platform/Account/Store/native object
Semantic Profile and complete affected-set
current lane and known consequence
official Spend, sales, profit and availability exposure
requester, Operations endorsement and Owner final approval
rationale, evidence, effective period and mandatory review
applicable policy/bundle version
```

In the initial Contract, any Exception that pauses a Protection or material Data
Repair Action SLO requires Owner final approval.

While valid it may:

```text
pause only the matching ordinary Action SLO
suppress duplicate Tasks and routine repeated notifications
keep the Case, lane, consequence, owner and expiry visible
continue fact acquisition, Freshness monitoring and recalculation
```

It cannot:

```text
make the Case healthy
create Outcome success
become a Guardrail pass or Approval
authorize Bid Increase, Bid Decrease or Compensation
substitute for Gate EV or Gate E
make an unverified Provider capability verified
```

A new Bid Preview or action intent requires the Exception to end and the same Case
and Task to reactivate before current evidence and approval are rebuilt.

Expiry, missed review, increased exposure beyond the accepted scope, affected-set
or cause change, new sales/profit/availability regression, stale/conflicted
evidence, authority loss, governing-bundle change or repeated material condition
invalidates the Exception and reopens or escalates the same Case.

There is no automatic renewal, lightweight extension or permanent hidden
suppression.

### 6.13 Governed Manual Shadow and configuration verification

Both platforms must provide a complete Governed Manual Shadow path.

An exact Manual Execution Packet binds:

```text
Platform/Account/Store/native object
current observed configuration
exact intended target or state
complete affected-set
reason and Evidence
Guardrails and blockers
Maker, required approvers and expiry
expected impact and verification plan
Policy Bundle identity
```

Manual Shadow may cover Bid, Budget, Pause/Resume or another explicitly modeled
advertising action for which MarketOps has a deterministic recommendation and
the actor has legal platform authority. Only `AD_BID_CHANGE` is the selected API
controlled-write Capability in this Slice. A Manual Packet never creates a
hidden Provider API path.

Configuration evidence uses this hierarchy:

```text
1. verified official API Readback
2. verified replayable official configuration export
3. independent scope-authorized manual configuration verification
4. executor self-report only
```

Independent manual verification requires a verifier distinct from the executor,
an exact Packet, legal configuration-read scope, exact native object/field/value,
actual observed-at time, accepted evidence Profile and no known conflict or later
unresolved change.

Manual verification may prove only the official console state actually observed.
It does not prove API idempotency, exact application time without evidence,
Provider acceptance semantics or business Outcome success.

Executor self-report or an incomplete screenshot remains
`ACTION_REPORTED_CONFIGURATION_UNVERIFIED`.

A confirmed or uncertain manual action consumes the same affected-set
reservation and applicable aggregate exposure as a controlled write.

### 6.14 Selected controlled write — `AD_BID_CHANGE`

The sole new controlled-write business Capability is:

```text
AD_BID_CHANGE
```

It applies only to a stable verified native object with:

```text
explicit manual Bid field
verified bidding mode
Fresh exact current Bid
verified currency, unit, precision, min/max and step
current Capability/Credential/Permission
exact complete affected-set
versioned Semantic Profile
official Readback
active feature flag and Gate scope
```

It does not authorize:

```text
Budget change
Campaign pause/resume
strategy or bidding-mode switch
target creation/deletion
Campaign structure change
automatic pacing
portfolio reallocation
```

Engineering supports three separately gated business directions:

```text
PROTECTION_DECREASE
OPTIMIZATION_INCREASE
EXACT_PRIOR_BID_COMPENSATION
```

Protection Decrease requires Fresh one-sided danger and its exact candidate
basis. Optimization Increase requires complete, Fresh, sample-sufficient,
write-grade evidence and all hard safety Gates. Compensation follows its separate
strict authority.

Real enablement is independent by Platform, Account/Store, native object kind,
direction and candidate basis.

### 6.15 Deterministic exact Bid target authority

All ordinary Protection and Optimization targets come from one versioned:

```text
AD_BID_TARGET_POLICY
```

The Policy separates:

```text
write-grade economic ceiling
direction-specific change envelope
finite deterministic exact candidate set
```

Marketplace Operator may select or reject an exact generated candidate, choose
Governed Manual Shadow or request Policy review. The Operator cannot free-type a
different controlled value.

Provider normalization and valid-step conversion occur before Preview and
Approval. Adapter runtime rounding or silent target substitution is prohibited.

#### `MAX_CPC_BOUNDED`

When write-grade Max CPC exists:

- Optimization Increase cannot exceed the conservative Provider-valid ceiling;
- Protection uses the ceiling as an economic reference, not an automatic target;
- a bounded Protection intermediate target may remain above the ceiling only when
  the Policy explicitly permits it and the result remains
  `RECOVERY_IN_PROGRESS_NOT_HEALTHY`.

#### `CAUSE_BOUND_PROTECTION_STEP`

When write-grade Max CPC is unavailable, one bounded lower candidate may be
generated only when:

```text
lane is PROTECTION
Fresh one-sided danger proof is satisfied
danger cause is exact and versioned
unknown conversion cannot reverse the danger
native object, current Bid and affected-set are complete
Completed-Sales Guard has not failed
no active Accepted Exception exists
an exact Cause-bound Target Policy is active
```

The Preview must state:

```text
write-grade Max CPC unavailable
candidate basis is cause-bound protection
target limits current exposure only
target does not prove optimality, profitability or health
```

Provider-native or estimated conversion, estimated Max CPC, similar-SKU
inference and AI-proposed magnitude have no controlled target authority.

### 6.16 Impact, materiality and approval

Materiality is a deterministic, versioned, non-compensating multi-axis policy
covering, as applicable:

```text
direction
single absolute and relative Bid change
official Spend exposure
complete affected-set and critical-sales exposure
cumulative change/exposure
lifecycle and governed cohort
Regression / Quarantine / Unknown status
```

Any hard material trigger is sufficient; low exposure on another axis cannot
offset it.

Initial operating state:

```text
every nonzero AD_BID_CHANGE = MATERIAL_IMPACT
ordinary nonzero envelope = 0
standing Policy automation = disabled
```

The initial Material route is:

```text
scoped Marketplace Operator Maker
→ distinct Operations Lead operational endorsement
→ Human Owner final per-command approval
```

Engineering must also implement:

```text
ORDINARY_IMPACT
MATERIAL_IMPACT
MATERIALITY_UNRESOLVED
```

A later Ordinary route may be enabled only by an exact, direction/candidate-
basis/scope-specific promotion evidence bundle after all-material Pilot history,
mature Operational and applicable Settled outcomes, proven Operations control and
no unresolved systemic defect.

The promoted Ordinary route remains:

```text
Marketplace Operator Maker
→ distinct Operations Lead final per-command approval
```

It is not Standing automation. Compensation, Regression, Quarantine, Unknown,
critical protected-sales exposure and other fixed material triggers remain
Material or prohibited.

### 6.17 Impact Preview and Approval Lease

Every executable action has an exact Impact Preview showing:

```text
current Bid
exact target Bid
direction and candidate basis
complete affected-set and critical sales units
official Spend and current exposure
sales, availability, sellability and profit Gates
write-grade Max CPC or explicit absence
Metric, Policy, Profile and Bundle versions
Provider unit/step and exact submitted value
materiality route
aggregate exposure and reservation state
known uncertainty, blocked alternatives and expected verification
```

Every executable Approval has an explicit `expires_at`.

Effective expiry is the earliest of:

```text
applicable scoped Approval Lease
Recommendation/Preview validity
each required evidence purpose validity
Metric/Policy/Profile/Bundle effective period
Credential/Permission/Capability validity
Gate EV/Gate E window
a shorter Owner-selected expiry
```

Validity is rechecked at final approval, Command creation, worker execution
preparation, immediately before Provider transmission and same-command retry
eligibility.

Expired Approval cannot be extended or resurrected. A new current Preview and
full approval chain are required. Kill, Quarantine or Bundle replacement
permanently invalidates affected old executable assets.

### 6.18 Command, idempotency and unknown-result convergence

One logical action creates one internal `AD_BID_CHANGE` Command identity. All
eligible attempts preserve the same:

```text
Platform/Account/Store/native object
current authority snapshot
exact target Bid
affected-set digest
Approval
Policy Bundle
Command identity
Provider idempotency identity when supported
```

A Retry cannot change the target, Provider identity or business Scope.

Provider behavior is Capability-profiled.

#### Verified Provider-native idempotency

When official/account evidence proves same-key/same-payload identity and operation
status semantics, the same Command may use the same Provider identity within a
bounded Retry Budget after status/Readback-first resolution.

#### No verified Provider-native idempotency

The default is one Provider submission. A Timeout plus repeated observation of
the prior Bid does not prove non-application.

The same Command may be retried only when official Provider evidence explicitly
and replayably proves:

```text
the first request was not accepted or was terminated before application
the current Bid still equals the captured prior Bid
the error/state is documented and verified retryable
all current business and execution authorities remain valid
```

Without that proof, the Command remains in factual Readback/Manual Resolution.

While one Command is unresolved, a replacement or parallel Command for that
native object is prohibited.

### 6.19 Provider success and Readback

Provider acceptance or HTTP success is not final success.

Controlled configuration success requires:

```text
required native operation state converged
AND
official current Bid equals the exact approved native target
after only the pre-approved versioned representation normalization
```

A tolerance, nearest value, runtime rounding or unapproved economic substitute is
not a match.

Required outcomes include:

```text
PENDING
LEASED
EXECUTING
PLATFORM_PENDING
READBACK_PENDING
READBACK_MATCHED
UNKNOWN_REQUIRES_READBACK
READBACK_MISMATCH
LATER_CHANGE_OR_MISMATCH_INVESTIGATION
MANUAL_RESOLUTION
FAILED_FINAL
COMPENSATION_PENDING
COMPENSATED
COMPENSATION_FAILED
TERMINATED_WITHOUT_PROVIDER_CALL
```

A third value or external/unresolved later change is never overwritten. It enters
Mismatch/Later-change investigation.

### 6.20 Same-object reentry and exact Compensation

Generic same-object second-command entry is disabled in the initial production
policy pending real Release/Pilot calibration.

A prior action must complete its required execution and early observation
responsibility before any future general reentry Profile could permit another
ordinary action. Time, Provider state, observation and business-result conditions
must be versioned and evidence-backed; no source default exists.

The only initial bypass is a human-triggered exact prior-Bid Compensation within
the original action lineage.

Compensation is eligible only when:

```text
the prior Command Readback matched
an action-bound Stop Condition or Regression is satisfied
current official Bid still equals the prior Command target
captured immutable prior Bid is available
no later external or unresolved change owns the current state
current identity, permission, capability and hard safety Gates pass
a new exact Preview is created
a scoped Marketplace Operator makes the request
Operations Lead endorses
Owner gives a new final approval
the exact Gate EV or Gate E scope includes Compensation
```

Compensation target is the captured exact prior Bid only. It is not adaptive
re-optimization. Automatic rollback is prohibited. Compensation Readback is not
business Outcome success.

### 6.21 Affected-set action observation reservation

An active governed advertising intervention reserves the complete affected-set.

Reservation begins for:

```text
controlled AD_BID_CHANGE
confirmed-to-be-executed Governed Manual Packet
exact prior-Bid Compensation
```

A Recommendation, Watch, Data Repair task or unexecuted revocable Packet does not
consume an active reservation.

A new governed advertising action whose affected-set overlaps an active
reservation is blocked. Non-overlapping affected-sets may proceed, subject to the
aggregate Pilot envelope.

Protection/Regression receives deterministic precedence over ordinary
Optimization.

Reservation release requires at least:

```text
Provider or manual configuration state resolved
no Unknown or Mismatch
first required eligible Early Completed-Sales observation complete
no unresolved action-associated Regression
```

It does not wait for 30-day Retained Sales unless the active Gate explicitly
requires that stricter condition.

Exact Compensation remains inside the original reservation.

A known material overlapping price, promotion, Listing, sellability or other
cross-domain change makes isolated controlled action unavailable. An unexpected
later change keeps the early safety Guard active but makes final action-specific
Outcome confounded or requires rebaseline.

### 6.22 Aggregate active-intervention exposure

Every Gate E must include a versioned, non-compensating multi-axis aggregate
Exposure Envelope.

At minimum it independently bounds:

```text
simultaneously active governed interventions
deduplicated affected-set share of Store Retained Sales
official advertising Spend associated with active interventions
cumulative absolute Bid-change exposure in the governed window
unresolved transmitted write count
reserved exact-Compensation or incident-response headroom
```

Every hard axis must pass. Protection Decrease cannot offset Optimization
Increase. Low sales exposure cannot offset excessive Spend. Expected profit
cannot offset unresolved Provider state.

Governed Manual and controlled real interventions consume the same applicable
business exposure.

Unknown/Mismatch consumes active-intervention and unresolved-write capacity until
factual resolution.

Reserved recovery headroom cannot be borrowed by ordinary new actions. If API
Compensation is not Gate-authorized, the Envelope must identify the governed
manual restoration capacity instead of implying API recovery.

Aggregate capacity is rechecked immediately before Provider transmission. An
Approval does not guarantee future capacity and waiting does not extend its
Lease.

### 6.23 Comparable baseline and confounder authority

Every governed manual or controlled action freezes an
`OUTCOME_EVALUATION_PLAN` before external execution.

It binds at least:

```text
atomic Case and complete affected-set
native object lineage
action time and exact configuration
Metric/Cost/Fee/Allocation versions
Freshness/Qualification/Target/Outcome/Bundle versions
candidate pre-periods and eligible baseline set
post-action observation windows
coverage and sample requirements
known event exclusions and confounder policy
critical sales units
```

Canonical baseline selection is deterministic and owned by the existing Metric
authority. AI cannot choose a favorable baseline.

Comparable periods require the accepted equivalence or correction of business
scope, object lineage, affected-set, Metric/Cost/Fee definitions, sellability,
availability, price, promotion, Listing, other advertising action, source
coverage, business calendar and sample sufficiency.

A material confounder does not disable the early safety Guard, but the final
action-specific result is `OUTCOME_CONFOUNDED` or
`BASELINE_NOT_COMPARABLE`.

Insufficient history without an accepted experimental control produces
`OUTCOME_BASELINE_INSUFFICIENT`. The system cannot invent a benchmark or fill it
with an unrelated Hero SKU.

Operational association may be verified under a comparable plan. Causal
incrementality requires separately accepted Experiment or equivalent evidence.

### 6.24 Outcome Threshold Policy

A single versioned, scoped Outcome Policy defines:

```text
30-day Retained-Sales preservation tolerance
critical-sales-unit protection tolerance
material improvement for each profit axis
non-worsening band for each profit axis
minimum denominator and low-value handling
rounding and inclusive/exclusive boundary semantics
negative-profit terminal semantics
applicable Platform/Store/lifecycle/cohort scope
effective period, owner, version and evidence basis
```

The most specific unique applicable valid Policy resolves. Same-priority overlap
or conflict produces `OUTCOME_POLICY_CONFLICTED`. No complete Policy produces
`OUTCOME_POLICY_UNRESOLVED`.

When unresolved/conflicted:

```text
Queue observation continues
proven-harm Protection Task continues
final verified success is prohibited
verified Optimization is prohibited where it consumes the missing Policy
Optimization Bid Increase is prohibited
```

No hidden production threshold is embedded in source.

An action's Evaluation Plan freezes the applicable Policy version. Late data
recalculation uses that frozen version rather than a later more favorable
threshold.

### 6.25 Protection and primary efficiency outcomes

Cause-specific Protection results are distinct from primary efficiency success.

Required business distinctions include:

```text
VERIFIED_EFFICIENCY_SUCCESS
VERIFIED_AD_RISK_CLEARED
VERIFIED_AD_EXPOSURE_STOPPED
IMPROVED_NOT_HEALTHY
PROTECTION_IN_PROGRESS
OUTCOME_PENDING
OUTCOME_CONFOUNDED
```

`VERIFIED_AD_RISK_CLEARED` requires Fresh, cause-specific evidence proving the
original advertising danger no longer holds through the accepted window.

`VERIFIED_AD_EXPOSURE_STOPPED` requires exact affected scope, verified
configuration/observation and complete source evidence proving no new dangerous
advertising exposure, while separating late old charges from new Spend.

Neither result implies:

```text
inventory or sellability is repaired
company sales is preserved
primary efficiency success
```

Partial loss reduction with continuing proven harm cannot close the Protection
responsibility. Recurrence or invalidating late evidence reopens or escalates the
same lineage.

### 6.26 Two-stage advertising-profit Outcome

Profit Outcome has two explicit stages.

#### Operational stage

When 30-day Retained Sales, eligible Operational Advertising Contribution Profit,
the frozen comparable baseline and Outcome Policy, coverage/sample requirements
and confounder Gates are complete, the system may produce:

```text
OPERATIONAL_EFFICIENCY_SUCCESS
OPERATIONAL_NO_MATERIAL_IMPROVEMENT
OPERATIONAL_REGRESSION
```

Every state is visibly `OPERATIONAL_NOT_SETTLED`.

#### Settled stage

When Settlement, return/refusal loss, platform fees, official advertising Spend
corrections, cost and Adjustment facts mature for the same scope, the system uses
the same action lineage and frozen Outcome Policy with a stage-consistent Settled
baseline to produce:

```text
SETTLED_CONFIRMED_SUCCESS
SETTLED_CONFIRMED_NO_IMPROVEMENT
SETTLED_REGRESSION
SETTLEMENT_OUTCOME_UNRESOLVED
```

A Settled contradiction preserves the historical Operational version, reopens or
escalates the same Case/Outcome lineage, removes the action from
Settled-confirmed success reuse and creates the appropriate Finance/Advertising
review responsibility.

A favorable Settled result may likewise upgrade an earlier Operational
no-improvement result, with full version history preserved.

Settlement attribution gaps cannot be filled by invented allocation.

### 6.27 Outcome Regression quarantine

Early, Operational, Settled or invalidating late-evidence Regression creates a
cause-proportional `ACTION_OUTCOME_QUARANTINE`.

Initial scope includes:

```text
failed native object
complete affected-set
overlapping pending governed interventions
```

Unexecuted overlapping Preview, Approval, Command eligibility and Manual Packet
are invalidated, not parked for later reuse.

Root-cause expansion is proportional:

```text
local action-specific business failure
→ keep quarantine local to object/affected-set overlap

invalid Metric/Policy/Evidence/Profile version
→ quarantine every consumer of that version

Provider/Readback/Capability integrity defect
→ Platform + Account/Store + Capability Kill scope
```

Read, ingestion, reconciliation, calculation, investigation and audit continue.

Exact prior-Bid Compensation may remain separately eligible under its strict
authority. No Regression creates automatic reverse write.

Reenablement is never time-only. It requires cause-specific closure evidence,
current recalculation, resolution of Unknown/Mismatch, replacement or revocation
of invalid authority versions, applicable technical/security attestation,
Operations endorsement and Owner final approval.

### 6.28 Transmission-boundary quarantine

Quarantine or Kill becomes effective at its authoritative activation instant and
prevents any new external side effect from beginning.

After activation:

```text
unused Approval
→ invalidated for execution

PENDING Command with no Provider call
→ terminated without Provider call

LEASED but unsent Command
→ live pre-transmission revalidation blocks send

unexecuted Manual Packet
→ revoked

manual execution uncertain
→ configuration verification and reconciliation required
```

A request whose transmission already began remains a possible external fact. It
may perform status query, official Readback, reconciliation and
Unknown/Mismatch resolution only. It cannot Retry under the old Approval even if
the Provider later proves `NOT_APPLIED`.

Already applied historical actions remain facts and are not automatically
reversed.

### 6.29 Emergency Kill and reenablement

Emergency narrowing is asymmetric and fail-safe.

Deterministic hard triggers may activate an exact Hold/Kill. AI inference cannot.

A scoped Marketplace Operator may activate an entity/complete-affected-set Hold
within their actual action scope.

An Operations Lead may activate entity, affected-set or
Platform+Store+Capability Kill for business harm or execution-integrity concern
within their authority.

Platform Admin/Security may activate Platform+Account/Store+Capability Kill for
Credential, Provider, Schema, Adapter, Readback, Secret, security or execution
integrity concerns.

Activation is a one-way protective action. It requires reason, scope, actor or
trigger, evidence, activation time, review owner and append-only audit, but does
not wait for Owner availability before stopping new Provider transmission.

No single actor who can activate a Hold/Kill may unilaterally reenable.

Reenablement requires:

```text
classified and closed Root Cause
all transmitted Unknown/Mismatch resolved
invalid authorities replaced or revoked
affected calculations/results reconciled
current Credential/Permission/Capability/Readback evidence
exact new execution scope
Operations endorsement
Owner final approval
Platform/Security attestation when the cause is technical or security-related
```

Old decision assets never resurrect.

### 6.30 Scope-monotonic Gate EV and Gate E

Engineering closure is independent of real Provider side effects.

`RELEASE-V1-001` accepts the real operating foundation, including users, scopes,
read data, real Profiles, governed Shadow, runtime, monitoring, coverage and
release evidence.

Gate EV is a one-time or time-bounded, exact evidence-generation authority for:

```text
Platform
Account/Store
Capability
native object and allowlist
direction
candidate basis
current and target value
time window
approval actors
maximum change and cumulative exposure
abort/Kill owner
Readback and exact Compensation when included
```

Gate EV evidence never authorizes ongoing Pilot.

Gate E consumes a layered, scope-monotonic Pilot Evidence Bundle. It requires:

```text
accepted Release/operating foundation
governed Shadow and adoption evidence under preaccepted thresholds
direction/candidate-basis-specific real Gate EV
Approval/Lease/transmission/idempotency/Unknown/Readback/Kill evidence
early Completed-Sales safety observation
explicit operating ownership and coverage
exact aggregate Exposure Envelope
```

Gate E enabled scope must be a subset of demonstrated and accepted evidence.
Untested platform, direction, candidate basis, native object kind, Compensation
path, ordinary route or generic reentry remains disabled.

Thirty-day and Settled outcomes continue as Outcome obligations; predetermined
profit uplift is not a Pilot-entry requirement.

Future expansion requires new exact evidence and explicit Gate E Amendment.

### 6.31 Ordinary-route promotion

Every initial nonzero Command remains Material.

A later Ordinary route requires an independent
`AD_BID_CHANGE_ORDINARY_ROUTE_PROMOTION` bound to exact:

```text
Platform/Account/Store
direction and candidate basis
native object kind
lifecycle/cohort and entity eligibility
Materiality Policy and exact nonzero envelope
Freshness/Qualification/Target/Outcome versions
Aggregate Exposure and Approval Lease
operating coverage and effective period
Kill/revoke authority
```

The exact scope must first accumulate all-Material real history and satisfy
preaccepted evidence thresholds for:

```text
Shadow and human decision comparison
Provider Write/Readback and failure handling
Operations Lead judgment/control performance
30-day Operational outcomes
applicable Settled-confirmed outcomes
Regression/Inconclusive/Compensation/manual-resolution bounds
absence of unresolved systemic Metric/Policy/Provider defect
```

Evidence does not transfer across platform, direction, candidate basis or scope.

Ordinary remains per-command human Maker-Checker. Owner approves the delegation
Policy and may revoke/narrow it, but does not approve each exactly eligible
ordinary Command.

A settled/systemic/control failure suspends or revokes the affected promotion.
Old ordinary Approvals are invalidated and cannot be upgraded in place to
Material.

Standing Policy automation is outside this Contract.

### 6.32 Atomic Advertising Decision Policy Bundle

Each production decision scope resolves to one exact, complete, active:

```text
ADVERTISING_DECISION_POLICY_BUNDLE
```

The Bundle references, without re-owning, exact versions of:

```text
Capability/Semantic Profile
Freshness Profiles
Conversion Definition
Allowable CPA Definition
Optimization Qualification
Target Policy
Outcome Policy
Priority Policy
Human SLO Profile
Approval Lease
Aggregate Exposure Envelope
Ordinary Promotion Policy when applicable
```

It also binds exact Organization, Platform, Account/Store, Capability, direction,
candidate basis, native object kind, lifecycle/cohort/entity scope, effective
period, owner, evidence, activation and Kill/revoke authority.

Publishing a new domain version does not automatically change production write
authority. New versions may be used for Fixture, Shadow, impact analysis and
Bundle-candidate validation.

A Bundle is atomically activated only after whole-combination validation proves:

```text
all referenced versions are valid
Conversion and Allowable CPA stages match
Target candidates satisfy Provider semantics
purpose monotonicity holds
Materiality/Ordinary/Exposure/Lease rules are coherent
Provider and Readback semantics are valid
required Fixture/Shadow/Contract tests pass
Bundle scope does not exceed Release/Gate evidence
```

Any write-expanding or boundary-relaxing activation requires applicable domain
attestations, Operations endorsement and Owner final approval; Platform/Security
attestation is required when Provider/Credential semantics change.

Bundle activation invalidates all old unexecuted decision assets for that scope.
Already transmitted Commands converge under their original Bundle. Historical
Outcomes retain their frozen Evaluation Plan.

Rollback is a new audited Bundle version, never mutation of accepted history.

No unique complete active Bundle means the write path fails closed.

### 6.33 Live Queue, Daily Action Brief and Weekly Evidence Review

The Live Queue is the current Canonical work authority.

It shows current:

```text
lane and Canonical Priority
Task, assignee and human SLO
Approval and expiry
Exception, Hold, Quarantine and Kill
active intervention and aggregate exposure
Command, manual action, Provider state and Readback
Protection, Operational and Settled Outcome
```

Each configured operating day produces a versioned, `as_of` Daily Action Brief
covering data health, immediate Protection/Regression, Data Repair, qualified
Optimization and separate Watch, human responsibility, approvals/exceptions,
execution/aggregate exposure, Unknown/Mismatch/manual verification and recent
Outcomes.

Each configured week produces a versioned Weekly Evidence Review covering Shadow
accept/reject/modify reasons, governed actions, configuration verification,
early guards, Operational/Settled transitions, Regression/Quarantine/
Compensation, Exceptions, system/human SLOs, aggregate exposure, Policy Bundle
maturity, Gate E/Ordinary evidence and deferred Release obligations.

Daily and Weekly artifacts are read-only projections of Canonical authorities.
They cannot create a parallel Task, Approval, Metric, Outcome or Gate authority.

Late data never overwrites a published report. A revision or Delta lineage
preserves original `as_of`, source cutoff, calculation versions, Bundle versions,
gaps and publication time.

Role-based minimum disclosure applies to pages, APIs, exports, notifications,
attachments and AI summaries.

### 6.34 Targeted recalculation and hourly full reconciliation

A qualifying accepted, corrected, invalidated, expired or matured fact triggers
targeted recalculation of the exact affected advertising Cases, Tasks, decision
assets, reservations, exposure and Outcomes.

Trigger classes include:

```text
native advertising configuration/status/Spend/traffic/attribution
Product Mapping or affected-set
sellability and availability
Completed/Retained/Return/Settlement/Adjustment
COGS/fee/Allowable CPA/Conversion
Freshness/Qualification/Target/Outcome/Priority/SLO/Lease/Exposure
Policy Bundle activation/revocation
Exception/Hold/Kill/Quarantine
Provider Readback/Unknown/Mismatch
critical sales unit or confounder
Outcome maturity or Regression
```

For identical as-of facts, authority versions and Scope:

```text
targeted result = full-reconciliation result
```

A full advertising reconciliation runs at least once per 60 minutes and repairs
missed triggers, late/out-of-order facts, expired authority, stale decision
assets, incorrect Task/Exception/Reservation/Exposure state and unresolved
recalculable Outcomes.

Repeated calculation never creates a new Case identity.
## 7. Explicit non-goals and deferred product work

The Slice must not implement or imply:

- `STOCK_CHANGE`, replenishment quantity, purchase date, Allocation or Transfer;
- Overstock, Slow-moving, Ageing, Dead Stock or inventory-exit workflow;
- automated or API Budget change;
- automated or API Campaign pause/resume;
- bidding-strategy or bidding-mode switch;
- Campaign, ad-group, Target or Keyword creation/deletion;
- Campaign-structure editing;
- negative-keyword, search-term or creative/content write;
- promotion or Listing-content write;
- autonomous portfolio budget reallocation;
- a Portfolio Intervention that groups multiple Commands under one approval or
  permits cross-action risk netting;
- unattended Standing Policy execution of `AD_BID_CHANGE`;
- generic repeated same-object Bid tuning before separately calibrated authority;
- predictive elasticity, optimal-Bid or reinforcement-learning control;
- Provider-native attribution as company sales/profit truth;
- company total sales divided by ad Clicks as object conversion;
- estimated SKU Spend, estimated Conversion or estimated Max CPC as controlled
  write authority;
- causal incrementality claims without separately accepted evidence;
- permanent or hidden Accepted Exception suppression;
- automatic rollback or automatic Compensation;
- Provider cancel semantics not proven by official evidence;
- identical Ozon/Wildberries endpoint, object, attribution or Readback behavior;
- browser automation, scraping or unpublished Marketplace interfaces;
- a new Buyer PII, Secret, legal or cross-border processing purpose;
- external AI production invocation;
- real Ozon/WB calls during engineering;
- real OIDC/Yandex production activation during engineering;
- deployment, production migration, Gate EV, Gate E, Pilot or production write.

Manual Budget, Pause/Resume or other non-selected advertising actions may exist
only as governed human packets and attributable evidence within the accepted
Manual Shadow scope. They do not become hidden controlled Provider capabilities.

Future automation, portfolio control, additional advertising Commands and
Standing Policy authorization require a separate exact Slice/Capability Contract
or Human Owner-accepted additive Amendment.
## 8. Source of truth and authority matrix

| Concern | Sole authority |
| --- | --- |
| Organization/Account/Store topology | `organizationaccount` |
| Human authentication | approved OIDC identity boundary; real evidence deferred to Release |
| Business roles, scopes and data disclosure | `identityaccess` + `operationsworkflow` public contracts |
| Internal Variant and platform-listing identity | `productlisting` Product Master/Mapping authority |
| Native advertising object and Campaign/Target lineage | official Provider facts normalized by `marketplaceintegration` |
| Provider Capability, credential reference and invocation | `marketplaceintegration` |
| Official advertising configuration, Spend and traffic | official Marketplace API/report evidence |
| Provider-native attribution | official Provider observation, never company truth by itself |
| Company Orders/Completed/Retained/Settled sales | canonical order/return/finance authorities |
| Sellability and Listing state | `productlisting` plus official platform observation |
| Channel/company availability | inherited applicable SLICE-V1-002/inventory authorities |
| COGS, fees, Settlement and Adjustment | finance/ledger authority |
| Canonical advertising metrics and calculation versions | `analyticsdecision` |
| Deterministic ad-linked conversion | `analyticsdecision` consuming governed linkage evidence |
| Allowable CPA and Max CPC | versioned canonical Metric authority |
| Freshness, qualification, Target, Outcome, Priority, SLO, Lease and Exposure policy | `operationsworkflow` consuming exact domain Metric/Capability facts |
| Recommendation, Task, Approval and Accepted Exception | `operationsworkflow` |
| Advertising Decision Policy Bundle activation | governed cross-authority activation record; does not re-own domain facts |
| `AD_BID_CHANGE` Command/Outbox/Readback | `marketplaceintegration` execution boundary initiated by `operationsworkflow` |
| Manual configuration verification | governed Evidence/Workflow authority with source-grade distinction |
| Provider execution truth | official response/status plus exact Readback evidence |
| Operational/Settled Outcome | canonical Metric authority + `operationsworkflow` outcome lineage |
| Hold, Quarantine and Kill | scoped deterministic/role authority using existing workflow/capability controls |
| Audit and operability | `adminobservability` append-only audit and operational views |
| Daily/Weekly management artifacts | read-only versioned projections of Canonical authorities |
| AI explanation and drafting | `aicopilot`, explicitly non-canonical |

No Controller text, UI component, AI model, Adapter, report, import, Repository or
business module may become a second writer for another authority.
## 9. Binding invariants

1. Every query, mutation, disclosure, approval, manual packet, Command and report is Organization- and applicable Platform/Store/Product/Data/Action-scope constrained.
2. Frontend visibility is never authorization; backend and execution-boundary enforcement is mandatory.
3. An atomic Advertising Case preserves exact Provider-native object identity, object kind, lineage, bidding mode and complete affected-set.
4. A Provider object that cannot be independently controlled cannot be split into invented executable SKU or Target Cases.
5. Complete affected-set identity cannot be reduced to what the current viewer is allowed to see.
6. Ozon and Wildberries native semantics remain independently versioned behind one internal business envelope.
7. Unknown Provider object, field, mode, unit, state or mapping remains unknown and fails closed for every dependent purpose.
8. Official advertising Spend, traffic and configuration observations retain source time, ingestion time, correction history and Raw provenance.
9. A missing advertising metric is `NOT_AVAILABLE`, never zero.
10. Provider-native attribution remains observation and cannot become company sales, canonical ad-linked conversion or final profit by declaration.
11. Estimated SKU Spend allocation remains estimated and cannot independently authorize a write or Settled result.
12. Company total sales and Provider-attributed sales remain distinct facts.
13. Completed Sales are the early safety stage; 30-day Retained Sales are the primary final sales-protection stage.
14. Company total sales preservation cannot hide failure of a frozen required critical sales unit.
15. Growth in one Variant or channel cannot offset a required critical-sales-unit failure.
16. Missing required critical-unit evidence cannot be replaced by another unit's growth.
17. Operational and Settled Contribution Profit remain distinct stages and use stage-consistent baselines.
18. Canonical and Estimated profit never share the same truth or visual semantics.
19. Advertising Contribution Profit includes all applicable governed variable economics and official advertising Spend.
20. Primary efficiency success requires sales preservation plus the non-compensating dual-axis profit rule.
21. A still-negative profit result cannot become healthy through relative improvement.
22. Allowable CPA and conversion must use the same sale-event economic unit.
23. Company total sales divided by advertising Clicks cannot be canonical object conversion.
24. Deterministic ad-linked conversion requires complete governed linkage, affected-set, window, Freshness and sample evidence.
25. Material Provider-to-canonical attribution gaps fail closed for write-grade Max CPC.
26. Write-grade Max CPC is an economic ceiling, not an automatic Bid target.
27. Freshness is source-, platform-scope- and decision-purpose-specific.
28. A newly accepted old source fact is not automatically Fresh.
29. A mature cohort or still-effective policy is not automatically stale because its business period is old.
30. Write-grade Freshness and qualification cannot be weaker than the corresponding Recommendation grade.
31. Missing or conflicted purpose Profile blocks the consuming purpose without erasing an independent Fresh danger.
32. Freshness alone never proves sample sufficiency.
33. An immature, unsustained or immaterial opportunity remains Watch.
34. A decision-determinative data defect is Data Repair, not Watch.
35. Unknown alone is neither safety nor proven danger and cannot trigger a Bid direction.
36. Fresh one-sided danger may support Protection only when the missing fact cannot reverse that danger.
37. Optimization Increase requires complete write-grade evidence and every applicable hard Gate.
38. Every canonical lane and rank binds exact evidence and policy versions.
39. Canonical priority is lane-first, reason-coded, lexicographic and non-compensating.
40. Action/Outcome Regression and unresolved execution integrity cannot be buried by commercial scoring.
41. AI and free-form text cannot calculate or override the canonical lane or rank.
42. Repeated recalculation of one cause cannot create duplicate Cases or Tasks.
43. Routing follows the accountable cause owner, not the viewer.
44. Watch creates no automatic accountable Task.
45. Page open is not acknowledgement; acknowledgement is not action; action is not Outcome.
46. Reassignment never resets Case age or prior SLO history.
47. Outside staffed coverage, a paused staffed clock cannot hide wall-clock active harm and continuing exposure.
48. SLO breach creates escalation, never advertising write authority.
49. Accepted Exception preserves the calculated lane and known consequence.
50. Accepted Exception can pause only the matching ordinary Action SLO while valid.
51. Accepted Exception cannot become a Guardrail, Approval, write authority or Outcome success.
52. Accepted Exception and a new active Command disposition cannot coexist for the same cause.
53. Accepted Exception expiry, scope/cause/materiality change, evidence conflict, authority loss, recurrence or policy change reopens or escalates the same Case.
54. No automatic renewal, lightweight extension or permanent hidden exception suppression exists.
55. Manual Shadow cannot bypass any business, identity, permission, evidence or approval Gate.
56. Manual and controlled actions that change the real advertising environment consume the same reservation and applicable exposure.
57. Executor self-report alone cannot prove configuration state.
58. Independent manual verification proves only the state actually observed and never API idempotency or exact application time without evidence.
59. `AD_BID_CHANGE` is the only selected controlled-write family in this Slice.
60. `AD_BID_CHANGE` cannot switch strategy/mode, change Budget/status or restructure Campaign/Target objects.
61. Every controlled target is exact, Provider-valid and determined before Approval.
62. Provider normalization occurs before Approval; Adapter runtime rounding or target substitution is prohibited.
63. Marketplace Operator cannot free-type a controlled target outside the deterministic candidate set.
64. Estimated Conversion, estimated Max CPC, similar-SKU inference and AI magnitude have no controlled target authority.
65. Cause-bound Protection target may limit exposure without claiming optimality, profitability or health.
66. Materiality is multi-axis and non-compensating; any hard material trigger is sufficient.
67. Initially every nonzero `AD_BID_CHANGE` is Material and requires Owner final per-command approval.
68. Ordinary promotion cannot borrow evidence across Platform, Direction, Candidate Basis or scope.
69. Ordinary route remains per-command Maker-Checker and is not Standing automation.
70. Every executable Approval has an explicit non-renewable expiry no later than any bound authority.
71. Approval validity is rechecked immediately before Provider transmission and eligible Retry.
72. Expired, killed, quarantined or superseded Approval cannot be extended, rebound or resurrected.
73. One business action has one internal Command identity and one exact target.
74. Internal duplicate suppression does not prove external Provider idempotency.
75. Provider-native idempotency is used only under an exact verified Capability Profile.
76. Without verified Provider idempotency, Provider submission defaults to one attempt.
77. Prior Bid still visible after Timeout does not prove the first request was not applied.
78. Same-command Retry without Provider idempotency requires explicit verified `NOT_APPLIED` evidence and all current authorities.
79. An unresolved Command blocks replacement or parallel Command for the same native object.
80. Provider acceptance is not Readback match.
81. Readback success requires exact approved native target equality after only pre-approved representation normalization.
82. A third value or later external/unresolved change is never overwritten.
83. Generic same-object reentry is initially disabled pending calibrated authority.
84. Exact Compensation target is the captured immutable prior Bid only.
85. Automatic rollback and automatic Compensation are prohibited.
86. Compensation cannot overwrite a later legitimate or unresolved external change.
87. Compensation Readback is not business Outcome success.
88. Overlapping affected-set governed interventions cannot be active concurrently before reservation release.
89. Non-overlapping actions may proceed only within the aggregate Exposure Envelope.
90. Reservation release requires resolved configuration, required early observation and no unresolved Regression.
91. Known material cross-domain change blocks isolated controlled action; unexpected change confounds final attribution.
92. Aggregate Exposure axes cannot offset one another or net Protection against Optimization.
93. Unknown/Mismatch continues to consume active and unresolved-write capacity.
94. Ordinary actions cannot consume reserved Compensation or incident-response headroom.
95. Approval does not guarantee later aggregate capacity and waiting never extends its Lease.
96. Outcome Evaluation Plan and required critical sales units are frozen before external action.
97. Canonical baseline selection is deterministic; AI or post-result preference cannot choose it.
98. Material confounder preserves early safety response but blocks an action-specific final verdict.
99. Insufficient comparable history cannot be replaced by an invented benchmark.
100. Operational association is not causal incrementality.
101. Outcome threshold policy is versioned, scoped and frozen for the action.
102. Missing or conflicted Outcome Policy cannot silently produce success or Optimization write eligibility.
103. Protection risk-cleared or exposure-stopped outcomes remain distinct from primary efficiency success.
104. Partial improvement with continuing proven harm cannot close the Protection responsibility.
105. Settled contradiction preserves historical Operational truth and reopens or escalates the same lineage.
106. Settlement attribution gaps cannot be filled by invented allocation.
107. Regression quarantine initially covers the failed object, complete affected-set and overlapping pending interventions.
108. An invalid shared authority version quarantines every dependent consumer.
109. A Provider integrity defect activates the exact Platform+Account/Store+Capability boundary, not an unrelated global stop.
110. Quarantine or Kill prevents any new external side effect from beginning after its authoritative activation instant.
111. PENDING or LEASED-but-unsent work has no grandfathered right to transmit after Kill/Quarantine.
112. Already transmitted work is resolved factually and cannot be pretended cancelled.
113. A Provider `NOT_APPLIED` result obtained after quarantine does not restore the old Approval's Retry authority.
114. Emergency stop may be fast and scoped; reenablement is cause-specific, evidence-backed and multi-party.
115. No actor who can unilaterally stop may unilaterally restart.
116. Reenablement never restores old Recommendation, Preview, Approval, Command or Manual Packet assets.
117. Gate EV is exact bounded evidence-generation authority only.
118. Gate E enabled scope is never broader than demonstrated and accepted evidence.
119. Untested Platform, Direction, Candidate Basis, Compensation path, Ordinary route or generic reentry remains disabled.
120. Gate E does not require predetermined business uplift, but early safety and continuing Outcome obligations remain mandatory.
121. Every production decision scope resolves to one exact complete active Advertising Decision Policy Bundle.
122. Publication of a domain version does not independently change production write authority.
123. Bundle activation is atomic, whole-combination validated and scope-bound.
124. Bundle replacement invalidates old unexecuted assets but cannot rewrite transmitted Commands or historical Outcomes.
125. Bundle rollback is a new audited version, never mutation of accepted history.
126. Live Queue is the current work authority; Daily and Weekly artifacts are read-only versioned projections.
127. Late data creates report revision/delta lineage and never silently overwrites a published management artifact.
128. Role-specific views cannot change the canonical result or leak unauthorized fields through API, export, notification, attachment, error or AI.
129. Targeted and hourly full reconciliation are equivalent for identical as-of facts, authorities and scope.
130. A missed targeted trigger is recovered by the next successful hourly reconciliation.
131. No Secret, Buyer PII, unredacted production payload or real Credential enters Git, fixtures, logs, client bundles, general Mart or AI.
132. No real Provider call or production write exists in engineering evidence.
133. Applied migrations and historical evidence remain byte-identical; all new schema change is forward-only.
134. Shared ingestion, Metric, Policy, workflow, Command, Readback and audit authorities are reused; no parallel stack is created.
135. `production_write_enabled` remains `false` until a separate exact authority changes an exact scope.
## 10. Observable state semantics

Exact enum and persistence names may evolve, but the product must preserve at
least the following business distinctions.

### 10.1 Evidence and decision eligibility

```text
CANONICAL_CONFIRMED
OPERATIONAL
PROVISIONAL_OR_ESTIMATED
FRESH
STALE
INCOMPLETE
CONFLICTED
UNKNOWN
NOT_AVAILABLE
DATA_BLOCKED
POLICY_BLOCKED
PROFILE_UNRESOLVED
BUNDLE_UNRESOLVED
```

### 10.2 Lane and work

```text
PROTECTION
DATA_REPAIR
OPTIMIZATION
WATCH

OPEN
ASSIGNED
ACKNOWLEDGED
ACTION_DECIDED
ACTION_RECORDED
VERIFYING
ACCEPTED_EXCEPTION_ACTIVE
REOPENED
ESCALATED
REWORK_REQUIRED
CANCELLED
```

`ACCEPTED_EXCEPTION_ACTIVE` is a disposition, not a replacement for the
calculated lane.

### 10.3 Recommendation and Approval

```text
DRAFT
VALIDATED
READY_FOR_REVIEW
ORDINARY_IMPACT
MATERIAL_IMPACT
MATERIALITY_UNRESOLVED
ENDORSED
APPROVED
REJECTED
EXPIRED
REVOKED
INVALIDATED_BY_EVIDENCE
INVALIDATED_BY_BUNDLE
INVALIDATED_BY_KILL_OR_QUARANTINE
COMMAND_CREATED
```

### 10.4 Controlled and manual execution

```text
PENDING
LEASED
EXECUTING
PLATFORM_PENDING
READBACK_PENDING
READBACK_MATCHED
UNKNOWN_REQUIRES_READBACK
READBACK_MISMATCH
LATER_CHANGE_OR_MISMATCH_INVESTIGATION
MANUAL_RESOLUTION
FAILED_FINAL
TERMINATED_WITHOUT_PROVIDER_CALL
COMPENSATION_PENDING
COMPENSATED
COMPENSATION_FAILED

MANUAL_PACKET_ISSUED
MANUAL_PACKET_REVOKED
ACTION_REPORTED_CONFIGURATION_UNVERIFIED
MANUAL_CONFIGURATION_VERIFIED
MANUAL_EXECUTION_UNCERTAIN
```

### 10.5 Reservation, exposure and containment

```text
ACTION_OBSERVATION_RESERVATION_ACTIVE
OVERLAPPING_AD_ACTION_BLOCKED
AGGREGATE_EXPOSURE_UNRESOLVED
AGGREGATE_ENVELOPE_BLOCKED
EMERGENCY_ENTITY_HOLD
ACTION_OUTCOME_QUARANTINE
CAPABILITY_QUARANTINED
KILL_SWITCH_ACTIVE
REENABLEMENT_REVIEW
```

### 10.6 Outcome

```text
PROTECTION_IN_PROGRESS
IMPROVED_NOT_HEALTHY
VERIFIED_AD_RISK_CLEARED
VERIFIED_AD_EXPOSURE_STOPPED

OPERATIONAL_EFFICIENCY_SUCCESS
OPERATIONAL_NO_MATERIAL_IMPROVEMENT
OPERATIONAL_REGRESSION
OPERATIONAL_NOT_SETTLED

SETTLED_CONFIRMED_SUCCESS
SETTLED_CONFIRMED_NO_IMPROVEMENT
SETTLED_REGRESSION
SETTLEMENT_OUTCOME_UNRESOLVED

OUTCOME_BASELINE_INSUFFICIENT
OUTCOME_CONFOUNDED
BASELINE_NOT_COMPARABLE
OUTCOME_POLICY_UNRESOLVED
OUTCOME_POLICY_CONFLICTED
```

No implementation may collapse Provider acceptance, Readback, configuration
verification, protection completion, Operational success and Settled confirmation
into one generic `SUCCESS`.
## 11. Internal response SLO and operability

The MarketOps internal clock starts at `fact_accepted_at`. A known expiry uses
the exact expiry instant.

### Protection and Regression targeted path

```text
Card and required Task create/update/reopen/escalate:
P95 <= 5 minutes

hard acceptance bound:
<= 15 minutes
```

### Other targeted paths

```text
Data Repair, qualified Optimization, Watch and
Exception/Hold/Outcome state update:
hard acceptance bound <= 15 minutes
```

### Full reconciliation

```text
advertising full reconciliation:
at least once per 60 minutes

missed-trigger recovery:
by completion of the next scheduled successful hourly reconciliation
```

At the declared acceptance capacity, the full sweep must complete with sufficient
margin to sustain the cadence. A missed cadence, backlog or SLO breach becomes an
operator-visible incident.

Source latency and MarketOps internal latency are measured separately.

Evidence exposes at least:

```text
source_event_time
source_updated_at
ingested_at
fact_accepted_at
calculated_at
lane_and_priority_at
task_activated_or_updated_at
acknowledgement_at
action_decided_or_recorded_at
approval_at
approval_expires_at
provider_transmission_started_at
provider_status_observed_at
readback_observed_at
early_outcome_matured_at
operational_outcome_matured_at
settled_outcome_matured_at
reconciliation_started_at
reconciliation_completed_at
```

The engineering Slice proves internal SLO with deterministic fixtures and
isolated runtime. It does not claim real Marketplace end-to-end freshness,
human staffing or Provider latency before Release evidence.
## 12. Compatibility, migration and recovery

- inspect the exact protected-main migration inventory before design;
- never edit an applied migration;
- use only new forward migrations when schema change is required;
- preserve V0001-V0010 byte identity and all accepted historical evidence;
- clean install and upgrade from protected `main` must both pass;
- migration failure must leave a diagnosable, recoverable state;
- keep Provider DTO/SDK types outside domain/core/public product contracts;
- preserve old Command, Approval, Bundle and Outcome versions after schema
  evolution;
- derived advertising projections, lanes, priority and reports must be rebuildable
  from authoritative facts and versions;
- rebuild cannot silently rewrite Task, Exception, Approval, Command, Readback,
  quarantine, audit or published-report history;
- worker restart/replay cannot duplicate source facts, Cases, Tasks, Commands,
  Attempts, reservations, exposure or audit events;
- late facts and Policy/Bundle changes trigger attributable recalculation;
- no recovery operation invents a Provider result or overwrites a later legitimate
  external change;
- rollback means safe application rollback or forward-fix according to migration
  class; destructive historical rewrite is prohibited.
## 13. Security, privacy, disclosure and audit

- backend authorization is mandatory for every Case, metric, evidence, Task,
  Recommendation, Preview, Approval, Packet, Command, Readback, Outcome,
  Exception, Hold, Kill, Bundle, report and export;
- users cannot read or mutate another Organization/Platform/Store/Product/Data/
  Action scope;
- the canonical evaluation always uses the full required evidence set; a narrow
  viewer cannot shrink the business Gate;
- the Marketplace Operator receives the minimum explicitly authorized decision
  view needed to identify the exact object, complete affected-set, current value,
  candidate, status and next accountable role;
- Operations Lead and final approver must possess the complete decision-evidence
  view required by their responsibility;
- missing approver evidence scope produces
  `APPROVAL_EVIDENCE_SCOPE_BLOCKED`;
- permission masking is not represented as a data gap, and a real data gap is not
  represented as permission masking;
- derived reasons, Gate results and summaries are sensitive information unless an
  explicit disclosure contract permits them;
- page, API, export, attachment, notification, error and AI projection enforce the
  same field and scope restrictions;
- no shared root Marketplace account is required or requested;
- read, write, finance, ads, credential administration, Kill and reenablement
  permissions remain separable;
- Secret material is resolved only inside the approved Adapter boundary and never
  appears in business DTOs, DB rows, logs, traces, UI or evidence artifacts;
- Buyer PII remains outside general Analytics/Mart and AI; this Slice introduces
  no new PII purpose or cross-border flow;
- synthetic or formally redacted data only is stored in repository and test
  artifacts;
- official APIs/reports and lawful official-console human operation are the only
  permitted Marketplace interaction modes; no browser automation, scraping or
  unpublished API is introduced;
- append-only audit covers every Policy/Profile/Bundle activation, decision,
  approval, expiry, Packet, Command, Attempt, Readback, manual verification,
  Exception, reservation, exposure, Regression, quarantine, Kill, reenablement,
  report publication and sensitive disclosure;
- audit events are attributable to actor or deterministic trigger, exact Scope,
  reason, evidence, server time and Correlation ID.
## 14. Execution Envelope

### Level 1 — authorized after exact Contract acceptance

Claude may continuously:

- read source, Git history and canonical docs;
- perform repository-native source understanding;
- create and evolve the Detailed Design;
- modify backend, frontend, tests, non-Secret config and canonical docs;
- create forward-only migrations;
- extend existing Shared Spine authorities only to the depth required here;
- run build, lint, typecheck, unit/property/architecture tests;
- run isolated PostgreSQL integration and migration tests;
- run local service/HTTP and browser E2E;
- use fake/mock/fixture Providers and ephemeral sandboxes;
- run performance, concurrency, restart, replay, reconciliation, security and
  failure-injection tests;
- create local Git branch, add, commit and exact checkpoints.

### Level 2 — not pre-authorized by this Contract

No shared non-production database, real Provider sandbox, real Seller Account,
shared integration environment or migration execution outside isolated/ephemeral
development is authorized.

An exact additive Amendment or dedicated authority is required if such access is
needed.

### Level 3 — separate authority

This Contract does not itself authorize:

```text
remote Git push, branch/tag mutation, PR or merge
production database or production migration
deployment or Terraform apply
real Credential or Secret access
real OIDC/Yandex activation
real Ozon/Wildberries read or write
Gate EV
Gate E
Pilot
destructive or irreversible operation
production business side effect
```

Any already-active remote-publication delegation may be used only by its named
delegate and within its own exact scope after an exact local checkpoint. This
Contract neither expands nor revokes that delegation.

Protected merge remains subject to independent Controller review, required
repository Gates and Human Owner authorization.
## 15. Stop conditions

Claude continues through difficult engineering problems while this Contract
remains satisfiable.

Stop and escalate only when evidence proves one of:

1. two binding Contract requirements cannot both be satisfied;
2. source/runtime truth disproves a core accepted business assumption;
3. a second writer, Metric, Policy, Command, Provider, audit or Bundle authority
   is required;
4. the implementation requires a new controlled advertising Command, Standing
   automation or Portfolio Intervention outside `AD_BID_CHANGE`;
5. the implementation requires `STOCK_CHANGE`, replenishment quantity,
   Allocation/Transfer or another deferred product capability;
6. Provider write semantics cannot bound unknown result, exact Readback or safe
   Compensation within this Contract;
7. implementation requires real Provider access, production Credential or a
   shared environment outside the accepted envelope;
8. implementation requires destructive/applied migration rewrite or irreversible
   historical loss;
9. a new Secret, Buyer PII, legal, data-localization or cross-border trust
   boundary is necessary;
10. the accepted SLO requires a materially new deployment topology rather than
    engineering optimization inside the accepted architecture;
11. an unavoidable Owner-level product, risk, cost or irreversible decision is
    not representable by this Contract;
12. source evidence proves the accepted selected Capability infeasible as an
    engineering business contract rather than merely unverified for a platform.

Transaction, lock, index, retry-budget mechanics, cache, package decomposition,
state-machine implementation, worker design, UI component choice and ordinary
refactoring are not pause reasons.
## 16. Required implementation artifacts

The implementation must deliver, in the same continuous work:

- exact canonical Contract bytes and Human Owner acceptance provenance;
- `docs/02-architecture/designs/SLICE-V1-003-design.md`;
- backend and frontend implementation;
- forward migration(s) only when required;
- API/OpenAPI changes and generated-client synchronization where applicable;
- Ozon and Wildberries advertising Fixture/Semantic/Capability profiles with
  clear synthetic or unverified identity;
- unit, property, architecture and PostgreSQL integration tests;
- migration clean-install and protected-main-upgrade evidence;
- browser E2E for Live Queue, Task, Manual Shadow, Approval, Command, Readback,
  Outcome, Exception, Quarantine, Kill and reports;
- deterministic Golden Dataset covering both platforms, all lanes, partial data,
  late data, overlapping affected-sets and dual-stage outcomes;
- mutation/adversarial tests proving every major Gate and no-write boundary;
- concurrency, idempotency, Unknown/Mismatch, transmission-boundary, reservation,
  aggregate-exposure and Compensation evidence;
- capacity/SLO and hourly-reconciliation evidence;
- security, disclosure, export, log and AI-projection evidence;
- runbooks for stale/incomplete ad data, Mapping/linkage gap, Provider incident,
  Approval expiry, Unknown result, manual verification, Reservation/Exposure
  block, Outcome Regression, Quarantine, Kill and reenablement;
- Live Queue, versioned Daily Action Brief and Weekly Evidence Review;
- canonical `CURRENT_STATE`, roadmap, decision log, open questions and V1
  traceability synchronization;
- `docs/07-phase-evidence/SLICE-V1-003/acceptance-status.md`;
- `docs/07-phase-evidence/SLICE-V1-003/executable-evidence.md`;
- `docs/07-phase-evidence/SLICE-V1-003/deferred-release-register.json`;
- machine-readable acceptance/evidence inventories sufficient for independent
  Controller review;
- exact local Git commit/tree and clean-worktree handoff.

Docs are Definition of Done, not a separate documentation Gate.
## 17. Production Acceptance criteria

Every criterion below requires executable evidence or an explicitly listed
deferred Release/Capability obligation. Engineering evidence uses synthetic,
fixture or formally redacted data and performs no real Provider side effect.

### A. Contract, predecessor and Shared-Spine authority

- `S3-AC-001` — repository contains the exact Human Owner-accepted Contract bytes and attributable acceptance evidence.
- `S3-AC-002` — canonical path, Contract SHA-256, source protected-main commit/tree and predecessor Snapshot identities are synchronized without mutation.
- `S3-AC-003` — canonical state advances to SLICE-V1-003 Full-Scope Implementation only after exact acceptance and preserves every SLICE-V1-002 accepted identity.
- `S3-AC-004` — roadmap preserves the Advertising & Traffic Efficiency outcome and records `AD_BID_CHANGE` as the selected controlled-write family.
- `S3-AC-005` — SLICE-V1-002 `STOCK_CHANGE`, replenishment quantity, Overstock, Allocation and Transfer remain unopened.
- `S3-AC-006` — Shared Spine architecture tests prevent a parallel ingestion, Metric, Policy, workflow, Command, Readback, Bundle or audit authority.
- `S3-AC-007` — conditional Design Gate remains untriggered or any later trigger follows the exact governance path.
- `S3-AC-008` — no Release, Gate EV, Gate E, Pilot, deployment or production-write authority is claimed by source completion, tests, merge or this Contract.

### B. Identity, platform semantics and scope

- `S3-AC-009` — every atomic Case binds Organization, Platform, Account/Store, minimum verified controllable native object, lineage, bidding mode and Semantic Profile.
- `S3-AC-010` — every atomic Case binds the exact complete affected Internal Variant set and a stable digest/version.
- `S3-AC-011` — a native object controlling multiple Variants cannot be split into separately executable estimated-SKU Cases.
- `S3-AC-012` — Campaign, ad-group, Target, Keyword and SKU relationships remain queryable without inventing unsupported Provider control granularity.
- `S3-AC-013` — Ozon and Wildberries Profiles preserve their different object, status, attribution, unit, precision, step, quota and Readback semantics.
- `S3-AC-014` — unknown native states/fields/modes fail closed for every dependent decision.
- `S3-AC-015` — a native object, lineage or bidding-mode change invalidates stale executable assets unless exact versioned continuity is proven.
- `S3-AC-016` — backend scope enforcement prevents horizontal and vertical Organization/Platform/Store/Product/Data/Action escalation.
- `S3-AC-017` — the Maker can identify the exact controlled object and complete affected-set without automatically receiving unauthorized cross-Store or finance detail.
- `S3-AC-018` — Operations Lead and final approver cannot endorse/approve without the evidence scope required by their responsibility.
- `S3-AC-019` — permission masking is distinguishable from an actual data gap, and Task/Approval participation grants no implicit access.
- `S3-AC-020` — API, export, notification, attachment, error and AI views enforce the same role/scope disclosure contract.

### C. Advertising facts, attribution, sales and profit

- `S3-AC-021` — official advertising configuration, Spend and traffic facts retain Raw provenance, source times, correction history and Profile identity.
- `S3-AC-022` — missing platform metrics remain `NOT_AVAILABLE`, never zero.
- `S3-AC-023` — late/corrected official Spend triggers attributable recalculation without overwriting history.
- `S3-AC-024` — Provider-native attributed Orders/Revenue/Conversion remain separately visible Provider observations.
- `S3-AC-025` — Provider attribution cannot by itself populate company sales, canonical ad-linked conversion or final profit.
- `S3-AC-026` — estimated SKU Spend remains visibly estimated and cannot independently support high-risk Recommendation, write or Settled result.
- `S3-AC-027` — company Order, Completed, Retained and Settled sale stages remain distinct.
- `S3-AC-028` — Completed Sales drive the early safety Guard; 30-day Retained Sales drive final sales protection.
- `S3-AC-029` — complete affected-set company total sales must pass the frozen preservation rule.
- `S3-AC-030` — every frozen required critical sales unit must independently pass; growth elsewhere cannot offset failure.
- `S3-AC-031` — missing critical-unit evidence yields unresolved/inconclusive rather than pass or zero.
- `S3-AC-032` — Advertising Contribution Profit is reconstructable from governed sales, COGS, fees, fulfillment, return loss, official Spend and applicable variable facts.
- `S3-AC-033` — absolute Advertising Contribution Profit and Contribution Profit per official advertising RUB are calculated under exact versions.
- `S3-AC-034` — primary efficiency success requires the sales rule plus one material profit-axis improvement and no material worsening on the other axis.
- `S3-AC-035` — a still-negative result remains `IMPROVED_NOT_HEALTHY` or Protection-in-progress rather than healthy success.
- `S3-AC-036` — Operational and Settled profit facts remain stage-distinct and expose evidence, window, version, Freshness, Confidence and gaps.

### D. Conversion, Max CPC, Freshness and qualification

- `S3-AC-037` — Provider-native conversion remains observation-only and company total sales divided by ad Clicks is rejected as canonical object conversion.
- `S3-AC-038` — canonical ad-linked conversion requires deterministic traffic-to-sale-event linkage for the exact object/scope and sale stage.
- `S3-AC-039` — material mapping/linkage/coverage gaps block write-grade Max CPC and expose a Provider-to-canonical attribution discrepancy.
- `S3-AC-040` — Allowable CPA economic unit exactly matches the conversion sale stage; tests reject stage mixing and duplicate loss counting.
- `S3-AC-041` — Max CPC is recalculable from exact stage-consistent versions and is visibly an economic ceiling, not a target.
- `S3-AC-042` — low-sample or estimated conversion cannot produce write-grade Max CPC.
- `S3-AC-043` — Freshness resolves by Evidence Kind, Platform/Account/Store/Semantic Profile and Decision Purpose.
- `S3-AC-044` — source age, report completeness, accepted-fact age, publication/correction lag, version validity, coverage and Provider incident may affect Freshness.
- `S3-AC-045` — a newly ingested old fact is not automatically Fresh; a mature cohort/effective policy is not automatically stale.
- `S3-AC-046` — write-grade Freshness cannot be weaker than corresponding Recommendation-grade Freshness.
- `S3-AC-047` — missing/conflicted Freshness Profile blocks only affected purposes and cannot erase an independent Fresh danger.
- `S3-AC-048` — Optimization Watch, Task, Recommendation and Bid Write qualifications are monotonically stronger.
- `S3-AC-049` — verified Optimization requires complete scope, coverage, sample sufficiency, sustained signal, material value and canonical profit.
- `S3-AC-050` — immature, unsustained or immaterial signals receive Watch reason codes and no automatic Task.
- `S3-AC-051` — a decision-determinative data defect becomes Data Repair rather than Watch.
- `S3-AC-052` — missing/conflicted real qualification Profile blocks Verified Optimization and Bid Increase while preserving observation.

### E. Lanes, priority, Task, SLO and Accepted Exception

- `S3-AC-053` — each Case deterministically resolves to Protection, Data Repair, Optimization or Watch with evidence and Policy versions.
- `S3-AC-054` — Fresh one-sided danger activates Protection only when unknown facts cannot reverse the dangerous direction.
- `S3-AC-055` — unknown alone cannot trigger increase, decrease, pause or success.
- `S3-AC-056` — lane-first Canonical Priority uses reason-coded hard sub-tiers and lexicographic non-compensating factors.
- `S3-AC-057` — Action/Outcome Regression, Quarantine, Unknown/Mismatch and Compensation responsibility enter Protection P0.
- `S3-AC-058` — sellability/availability/critical-sales danger enters P1; proven continuing economic harm enters P2 unless stronger.
- `S3-AC-059` — Data Repair and Optimization use their accepted independent lexicographic factor order; Watch ranking has no action authority.
- `S3-AC-060` — AI and user-personal sorting cannot change Canonical Rank, due time or escalation.
- `S3-AC-061` — Protection and material actionable Data Repair create/update one cause-routed Task; qualified Optimization does likewise; Watch does not.
- `S3-AC-062` — concurrency, replay and repeated calculation cannot duplicate a Case or active cause Task.
- `S3-AC-063` — independent causes with different owners may create explicitly linked separate Tasks.
- `S3-AC-064` — human SLO distinguishes acknowledgement from action decision/first attributable action.
- `S3-AC-065` — page open or free-text acknowledgement cannot satisfy the action stage.
- `S3-AC-066` — Protection/Regression response is no weaker than Optimization; material Data Repair has a cause-owner Profile.
- `S3-AC-067` — reassignment preserves Case age and SLO history.
- `S3-AC-068` — outside coverage, staffed-clock handling and `OUT_OF_COVERAGE_ACTIVE_HARM` exposure remain simultaneously visible.
- `S3-AC-069` — missing human SLO/Coverage Profile leaves Task active and blocks affected operating readiness rather than claiming on-time.
- `S3-AC-070` — Protection/Regression targeted update meets P95 <= 5 minutes and hard <= 15 minutes; other targeted state update meets hard <= 15 minutes.
- `S3-AC-071` — full reconciliation succeeds at least hourly and repairs a dropped targeted trigger while preserving any SLO violation.
- `S3-AC-072` — Accepted Exception preserves the calculated lane and exact known consequence.
- `S3-AC-073` — Exception requires exact cause/scope, evidence, consequence, Operations endorsement, Owner approval, period, review and Bundle.
- `S3-AC-074` — valid Exception pauses only matching ordinary Action SLO, suppresses duplicate work visibly, cannot coexist with action intent, and invalidation reopens the same Case without automatic renewal.

### F. Governed Manual Shadow and evidence

- `S3-AC-075` — both Ozon and Wildberries expose a complete governed Manual Shadow workflow.
- `S3-AC-076` — Manual Packet binds exact object, affected-set, current/target configuration, evidence, Guardrails, approval route, expiry, Bundle and verification plan.
- `S3-AC-077` — Budget, Pause/Resume or other non-selected manual actions do not create a hidden Provider API path.
- `S3-AC-078` — Manual action cannot proceed when identity, scope, hard evidence, permission or required approval is unresolved.
- `S3-AC-079` — verified official API Readback is accepted as strongest configuration evidence.
- `S3-AC-080` — verified replayable official configuration export can prove exact configuration under a valid source Profile.
- `S3-AC-081` — independent manual verification requires a distinct authorized verifier and records exact object/field/value/observed-at/evidence.
- `S3-AC-082` — executor self-report or incomplete screenshot remains configuration-unverified.
- `S3-AC-083` — manual verification cannot be represented as API Readback, Provider idempotency, exact application time or business success without evidence.
- `S3-AC-084` — manual and controlled real interventions consume the same affected-set reservation and applicable aggregate exposure.

### G. `AD_BID_CHANGE`, target, materiality and Approval

- `S3-AC-085` — `AD_BID_CHANGE` is the only new controlled-write family in this Slice.
- `S3-AC-086` — Budget, Campaign status, strategy/mode and Campaign/Target structure cannot be written through it.
- `S3-AC-087` — a controlled action requires verified explicit native Bid semantics, current Bid, unit/precision/step/min/max, capability, credential, permission and Readback.
- `S3-AC-088` — Provider-unverified paths are structurally unreachable and expose Shadow-only state.
- `S3-AC-089` — Protection Decrease and Optimization Increase are separately gated; exact Compensation uses its own authority.
- `S3-AC-090` — Optimization Increase requires complete write-grade Freshness, qualification, conversion/Max CPC, sales and inventory/sellability safety.
- `S3-AC-091` — Protection Decrease requires exact one-sided danger and a valid candidate basis.
- `S3-AC-092` — all ordinary targets come from one versioned `AD_BID_TARGET_POLICY`.
- `S3-AC-093` — Max CPC is never automatically selected as the target.
- `S3-AC-094` — the exact finite candidate set is deterministic for identical facts and Policy version.
- `S3-AC-095` — Marketplace Operator may select/reject candidates but cannot free-type outside the set.
- `S3-AC-096` — Provider valid-step normalization occurs before Preview/Approval; runtime rounding or target substitution fails acceptance.
- `S3-AC-097` — `MAX_CPC_BOUNDED` Increase never exceeds the conservative Provider-valid ceiling.
- `S3-AC-098` — a Protection intermediate target above the ceiling remains recovery-in-progress and not healthy.
- `S3-AC-099` — `CAUSE_BOUND_PROTECTION_STEP` requires an exact accepted danger cause whose missing conversion cannot reverse danger.
- `S3-AC-100` — cause-bound Preview states that Max CPC is unavailable and the target limits exposure only.
- `S3-AC-101` — estimated conversion/Max CPC, Provider observation, similar-SKU inference and AI magnitude never generate a controlled candidate.
- `S3-AC-102` — Materiality uses visible multi-axis non-compensating hard triggers.
- `S3-AC-103` — initial ordinary nonzero envelope is zero and every initial nonzero action follows Maker → Operations endorsement → Owner final approval.
- `S3-AC-104` — Maker cannot self-endorse or self-approve; unresolved Materiality fails closed.
- `S3-AC-105` — every Preview shows exact Before/After, affected-set, critical sales, Spend, profit, inventory/sellability, versions, Bundle, route and blockers.
- `S3-AC-106` — every executable Approval binds exact current/target, evidence snapshot, affected-set, Policy/Profile/Bundle and actors.
- `S3-AC-107` — every executable Approval has explicit expiry at the earliest bound authority limit and is rechecked through pre-transmission/Retry.
- `S3-AC-108` — expired, killed, quarantined, superseded or materially invalidated Approval cannot be extended/rebound/resurrected.
- `S3-AC-109` — later Ordinary promotion requires exact all-Material mature evidence for the same Platform/Direction/Basis/scope and remains per-command Operator/Operations Maker-Checker.
- `S3-AC-110` — fixed material triggers and Standing Policy automation cannot be downgraded or introduced by Ordinary promotion.

### H. Command, idempotency, Readback, reentry and Compensation

- `S3-AC-111` — one approved action creates one logical Command identity and duplicate submission cannot create a second logical effect.
- `S3-AC-112` — every eligible attempt preserves exact object, affected-set, target, Approval, Bundle and Provider identity where applicable.
- `S3-AC-113` — a Retry cannot change target, scope or Provider idempotency identity.
- `S3-AC-114` — verified Provider-native idempotency is used only under an evidence-backed Capability Profile and status/Readback precedes Retry.
- `S3-AC-115` — without verified Provider idempotency, default is one Provider submission.
- `S3-AC-116` — Timeout plus prior-value Readback never independently authorizes Retry.
- `S3-AC-117` — explicit verified `NOT_APPLIED`, unchanged prior Bid and current authority are required for no-idempotency same-command Retry.
- `S3-AC-118` — unresolved Command blocks replacement/parallel Command for the same native object.
- `S3-AC-119` — Provider acceptance/HTTP success cannot become configuration success.
- `S3-AC-120` — Readback match requires native convergence and exact approved target equality after accepted representation normalization only.
- `S3-AC-121` — tolerance, nearest-value acceptance, runtime rounding and third-value overwrite are rejected.
- `S3-AC-122` — generic same-object reentry is initially disabled pending separately accepted calibration.
- `S3-AC-123` — exact Compensation remains inside original lineage/reservation and targets captured immutable prior Bid only.
- `S3-AC-124` — Compensation requires prior matched Readback, Stop Condition/Regression, current-state ownership, current hard Gates, new Preview, Operations endorsement, Owner approval and exact Gate scope.
- `S3-AC-125` — automatic rollback/Compensation and overwrite of later legitimate/unresolved change are impossible.
- `S3-AC-126` — Compensation Readback is tracked separately from business Outcome.

### I. Reservation, exposure, quarantine, Kill and reenablement

- `S3-AC-127` — controlled or confirmed real manual intervention reserves the complete affected-set.
- `S3-AC-128` — overlapping affected-set action is blocked until configuration resolution, required early observation and no unresolved Regression.
- `S3-AC-129` — non-overlapping action remains eligible only inside aggregate Exposure.
- `S3-AC-130` — Protection/Regression receives deterministic precedence over ordinary Optimization for overlapping scope.
- `S3-AC-131` — exact Compensation uses original reservation; known material cross-domain change blocks isolation and unexpected change confounds/rebaselines Outcome.
- `S3-AC-132` — Gate E aggregate Envelope independently bounds active count, deduplicated Retained-Sales exposure, official Spend, cumulative absolute Bid change, unresolved writes and recovery headroom.
- `S3-AC-133` — all aggregate axes pass independently; no cross-axis/cross-direction netting exists.
- `S3-AC-134` — Unknown/Mismatch and governed manual interventions consume applicable aggregate capacity.
- `S3-AC-135` — reserved recovery headroom cannot be used by ordinary action.
- `S3-AC-136` — aggregate capacity is revalidated before transmission; Approval neither reserves capacity indefinitely nor extends while waiting.
- `S3-AC-137` — missing/conflicted aggregate Envelope blocks new controlled transmission.
- `S3-AC-138` — Early/Operational/Settled/late-evidence Regression creates object+affected-set+overlap quarantine and invalidates unexecuted overlap assets.
- `S3-AC-139` — invalid shared authority version quarantines all consumers; Provider integrity defect activates exact Platform+Account/Store+Capability scope.
- `S3-AC-140` — local business failure does not automatically stop unrelated valid non-overlapping scopes.
- `S3-AC-141` — quarantine activation prevents every new external side effect after the authoritative instant.
- `S3-AC-142` — PENDING and LEASED-but-unsent Commands terminate or fail transmission; already-transmitted requests continue status/Readback/reconciliation only.
- `S3-AC-143` — unexecuted Manual Packet is revoked; uncertain manual execution enters verification/reconciliation; applied action remains fact.
- `S3-AC-144` — deterministic hard trigger, scoped Operator Hold, Operations business Kill and Platform/Security integrity Kill enforce maximum authorized scope.
- `S3-AC-145` — Emergency stop records reason/evidence/actor/scope/time/review owner and need not wait for Owner availability.
- `S3-AC-146` — no actor who can activate a Hold/Kill may unilaterally reenable.
- `S3-AC-147` — reenablement requires Root Cause closure, Unknown/Mismatch resolution, current authority evidence, Operations endorsement and Owner approval.
- `S3-AC-148` — technical/security cause additionally requires Platform/Security closure attestation.
- `S3-AC-149` — reenablement never restores old decision assets.
- `S3-AC-150` — Exact Compensation may remain separately eligible during quarantine, but no Regression or Kill creates automatic reverse write.

### J. Baseline, outcomes and late financial truth

- `S3-AC-151` — every governed action freezes a complete Outcome Evaluation Plan before external execution.
- `S3-AC-152` — the Plan binds Case, affected-set, lineage, action, versions, baseline, windows, critical units, coverage, sample and confounder policy.
- `S3-AC-153` — canonical baseline selection is deterministic and cannot be chosen by AI or after result observation.
- `S3-AC-154` — material confounder keeps early safety Guard active but blocks action-specific final verdict.
- `S3-AC-155` — insufficient comparable history produces `OUTCOME_BASELINE_INSUFFICIENT`, not an invented benchmark.
- `S3-AC-156` — operational association and causal incrementality remain separate.
- `S3-AC-157` — Outcome Policy resolves by exact scoped effective version and has no hidden production default.
- `S3-AC-158` — Policy conflict/missing state blocks final success and dependent Optimization write while preserving observation/proven-harm Task.
- `S3-AC-159` — late data recalculation uses the action-frozen Outcome Policy.
- `S3-AC-160` — `VERIFIED_AD_RISK_CLEARED` proves only the original advertising danger cleared.
- `S3-AC-161` — `VERIFIED_AD_EXPOSURE_STOPPED` proves only no new dangerous exposure under complete evidence.
- `S3-AC-162` — Protection terminal state does not close inherited inventory/listing cause or imply primary success; continuing danger remains active.
- `S3-AC-163` — 30-day mature evidence can create formal Operational success/no-improvement/regression with visible not-settled status.
- `S3-AC-164` — Settled outcome uses the same lineage, frozen Policy and stage-consistent Settled baseline.
- `S3-AC-165` — Settled contradiction preserves Operational history and reopens/escalates the same lineage.
- `S3-AC-166` — favorable Settled evidence may upgrade earlier Operational no-improvement with history preserved.
- `S3-AC-167` — Settlement attribution unresolved cannot be converted to final result through estimated allocation.
- `S3-AC-168` — Outcome Regression removes settled-success reuse and creates the correct Finance/Advertising review responsibility.

### K. Gate, Bundle, cadence, UI, security, migration and evidence

- `S3-AC-169` — engineering can close without real Provider side effects only when deterministic interfaces and fail-closed states are complete.
- `S3-AC-170` — both platforms deliver full read/diagnosis/Task/Manual Shadow/Outcome business capability at engineering closure.
- `S3-AC-171` — Gate EV is exact Platform+Store+Capability+object+direction+basis+value+window+exposure+actor+abort authority and cannot authorize another scope.
- `S3-AC-172` — Gate E consumes operating foundation, Shadow, direction-specific Gate EV, failure controls, early safety, ownership and aggregate Exposure.
- `S3-AC-173` — Gate E scope is a subset of demonstrated evidence; untested Platform/Direction/Basis/Compensation/Ordinary/reentry remains disabled.
- `S3-AC-174` — 30-day and Settled outcomes remain continuing obligations rather than predetermined uplift prerequisites; future expansion requires exact evidence and Gate E Amendment.
- `S3-AC-175` — every production decision resolves to one complete active scope-bound Advertising Decision Policy Bundle.
- `S3-AC-176` — domain version publication alone cannot change production write eligibility.
- `S3-AC-177` — Bundle whole-combination validation covers stage consistency, Provider semantics, monotonicity, materiality, Lease, Exposure and Gate scope.
- `S3-AC-178` — write-expanding Bundle activation requires applicable attestations, Operations endorsement and Owner final approval.
- `S3-AC-179` — Bundle activation is atomic, invalidates old unexecuted assets without rebinding, and preserves transmitted/historical lineages.
- `S3-AC-180` — Bundle rollback creates a new audited version; missing/conflicted Bundle blocks candidate, Approval and transmission.
- `S3-AC-181` — Live Queue presents the current complete operating path with evidence drill-through and role scope.
- `S3-AC-182` — Daily Action Brief is produced each configured operating day as a versioned `as_of` action snapshot.
- `S3-AC-183` — Weekly Evidence Review covers Shadow, actions, verification, SLO, outcomes, Regression, exposure, Bundle/Gate maturity and release obligations.
- `S3-AC-184` — Daily/Weekly artifacts link canonical Cases/Tasks/Metrics/Outcomes and cannot become parallel writers.
- `S3-AC-185` — late facts create explicit Daily/Weekly revision or Delta lineage without overwriting prior publication.
- `S3-AC-186` — AI drafting uses authorized canonical references and cannot modify facts, state or authority.
- `S3-AC-187` — structured UI distinguishes confirmed, estimated, stale, blocked, Operational and Settled states and supports safe drill-through.
- `S3-AC-188` — API filtering/pagination and frontend navigation inherit backend scope and do not leak Provider DTO/SDK types.
- `S3-AC-189` — keyboard use, UTF-8/Russian text, UTC storage, Store-local display and safe errors are verified.
- `S3-AC-190` — Secret, Buyer PII, real Credential and unsafe Raw are absent from Git, fixtures, logs, traces, reports, AI and client bundles.
- `S3-AC-191` — no real Provider call, hidden Budget/Status/strategy write path or browser automation exists in engineering evidence.
- `S3-AC-192` — `production_write_enabled` remains false absent separate exact authority.
- `S3-AC-193` — applied migrations remain byte-identical; clean install and protected-main upgrade pass on real PostgreSQL.
- `S3-AC-194` — restart, replay, concurrency and reconciliation cannot duplicate facts, Cases, Tasks, Approvals, Commands, reservations, exposure, Outcomes or audit.
- `S3-AC-195` — unit/property/architecture/PostgreSQL/browser tests cover calculations, authority boundaries, state distinctions and full operating path.
- `S3-AC-196` — mutation/adversarial/fault-injection tests prove hard Gates, expiry, scope, transmission boundary, no-write, missed trigger, late data and Kill race.
- `S3-AC-197` — performance evidence proves targeted SLOs and hourly reconciliation at declared capacity.
- `S3-AC-198` — security tests prove role-minimal disclosure and no API/export/notification/AI bypass.
- `S3-AC-199` — Requirement/Owner Decision → Design → Code → Test → Evidence traceability and canonical docs/runbooks/evidence/Git identity are synchronized.
- `S3-AC-200` — full repository regression, governance/production-readiness validation and security scans pass with no threshold weakening and no unresolved BLOCKER/MAJOR finding.
## 18. Deferred Release and Capability obligations

The following obligations are mandatory and production-blocking for the exact
scope that consumes them. They do not block independent engineering implementation
or engineering closure when all deterministic interfaces, fail-closed states and
evidence placeholders are complete:

- `S3-REL-001` — real Ozon advertising read capability, native object hierarchy, Spend/traffic/attribution semantics, source maturity and account/store evidence.
- `S3-REL-002` — equivalent real Wildberries advertising read evidence without assuming Ozon symmetry.
- `S3-REL-003` — real Product Mapping, native-object lineage and complete affected-set coverage for the intended operating cohort.
- `S3-REL-004` — real company Order/Completed/Retained sales and deterministic ad-linked sale-event evidence, including Provider-to-canonical attribution-gap profile.
- `S3-REL-005` — real official advertising Spend reconciliation and correction-window evidence.
- `S3-REL-006` — real COGS, fee, fulfillment, return-loss, Settlement and Adjustment inputs sufficient for Operational and Settled advertising profit.
- `S3-REL-007` — real stage-consistent Conversion, Allowable CPA and Max CPC definitions and approved evidence.
- `S3-REL-008` — real source/platform/purpose Freshness Profiles for Queue, Task, Recommendation, Write, Compensation and Outcomes.
- `S3-REL-009` — real Optimization qualification, Target, Outcome, Priority, human-SLO, Approval-Lease and aggregate-Exposure Policies with owners and evidence.
- `S3-REL-010` — one complete, compatible, scope-bound Advertising Decision Policy Bundle accepted for each intended real operating scope.
- `S3-REL-011` — real OIDC/MFA, user disable/revoke, Role/Scope and role-minimal disclosure evidence.
- `S3-REL-012` — real Operating Calendar, staffed Coverage, escalation, Daily Brief and Weekly Review ownership.
- `S3-REL-013` — real governed Shadow evidence under preaccepted sample, duration, discrepancy, manual-verification and operating-SLO thresholds.
- `S3-REL-014` — real Ozon `AD_BID_CHANGE` endpoint, permission, unit, step, quota, idempotency/NOT_APPLIED, error, propagation and Readback evidence for each consumed scope.
- `S3-REL-015` — real Wildberries `AD_BID_CHANGE` capability evidence if that Provider path is to become reachable; otherwise verified Shadow-only fail-closed state.
- `S3-REL-016` — exact Gate EV envelope and Human Owner authorization before the first real write for each Platform/Store/Direction/Candidate Basis.
- `S3-REL-017` — exact Gate EV Write/Readback evidence and, when API Compensation is claimed, exact prior-value Compensation round-trip evidence.
- `S3-REL-018` — layered scope-monotonic Gate E Pilot Evidence Bundle, exact allowlist, aggregate Exposure, recovery headroom and operating ownership.
- `S3-REL-019` — real Provider-transmission Kill/Quarantine drill and cause-specific multi-party reenablement evidence.
- `S3-REL-020` — real early Completed-Sales Guard evidence for the Gate E entry scope and continuing 30-day/Settled Outcome obligations.
- `S3-REL-021` — real Operational/Settled baseline, critical-sales-unit and settlement-attribution evidence for mature Outcome reporting.
- `S3-REL-022` — real Ordinary-route promotion evidence before any Owner per-command delegation is activated; absent evidence keeps every nonzero action Material.
- `S3-REL-023` — Yandex runtime, managed PostgreSQL, immutable object custody, backup/PITR/restore, monitoring, security and legal/data-localization release evidence.
- `S3-REL-024` — Key User training/adoption, runbooks, release traceability and no unresolved release-blocking defect for the exact real scope.

These obligations are consumed by `RELEASE-V1-001`, exact Gate EV, exact Gate E,
an exact Ordinary-route promotion, or another separately accepted Release/
Capability authority.

They do not authorize deployment, Provider access, Pilot or production write.

`STOCK_CHANGE`, replenishment, Overstock, Allocation, Transfer and additional
advertising Commands are future product Capabilities, not deferred evidence rows
for this Slice.
## 19. Engineering closure state

When `S3-AC-001` through `S3-AC-200` are executably verified and all applicable
`S3-REL-*` obligations remain explicitly recorded as production-blocking, the
permitted engineering closure claim is:

```text
CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS
```

It is not:

```text
PRODUCTION_READY
DEPLOYED
PROVIDER_VERIFIED_FOR_ALL_PLATFORMS
PILOT_ENABLED
GATE_EV_AUTHORIZED
GATE_E_AUTHORIZED
ORDINARY_ROUTE_ENABLED
PRODUCTION_WRITE_ENABLED
```

Engineering closure requires both-platform business capability, complete Shared
Spine implementation and structural unreachability of every unverified Provider
write path. It does not require a real Provider call or predetermined business
uplift.
## 20. Owner decisions incorporated

| ID | Accepted decision |
| --- | --- |
| OD-S3-001 | Primary outcome: improve Advertising Contribution Profit efficiency while preserving current sales volume. |
| OD-S3-002 | Operations Lead is the primary decision owner; scoped Marketplace Operator is Maker; Finance owns financial truth; Owner retains material-risk approval. |
| OD-S3-003 | Atomic Case: minimum verified independently controllable native advertising object plus complete affected Internal Variant set. |
| OD-S3-004 | Common business Case envelope with versioned platform-native Ozon/Wildberries Semantic Profiles. |
| OD-S3-005 | Dual authority: Provider-native attribution remains observation; company qualified sales and canonical profit govern business protection/outcome. |
| OD-S3-006 | Completed Sales provide the early safety Guard; 30-day Retained Sales provide the primary final sales-protection result. |
| OD-S3-007 | Dual-axis Pareto efficiency: absolute Advertising Contribution Profit plus Contribution Profit per official advertising RUB, with sales preservation. |
| OD-S3-008 | Loss reduction that remains negative is `IMPROVED_NOT_HEALTHY`, never healthy efficiency success. |
| OD-S3-009 | Directional asymmetric fail-closed behavior: full proof for Increase; Fresh one-sided danger may support Protection; unknown alone has no direction authority. |
| OD-S3-010 | Deterministic lanes: Protection, Data Repair, Optimization and Watch. |
| OD-S3-011 | Both platforms receive a full governed Manual Shadow path plus exactly one selected controlled-write Capability. |
| OD-S3-012 | The selected controlled-write Capability is `AD_BID_CHANGE`; Budget/status/structure remain manual or future scope. |
| OD-S3-013 | Separate bid directions: Protection Decrease and verified Optimization Increase; exact prior-Bid Compensation is a distinct recovery path. |
| OD-S3-014 | Maker-Checker approval: scoped Operator Maker; Operations endorsement; Owner final approval for Material; later Ordinary uses distinct Operations final approval. |
| OD-S3-015 | Materiality is deterministic, multi-axis and non-compensating. |
| OD-S3-016 | Initial Ordinary nonzero envelope is zero; every nonzero Bid Command is Material; Standing automation is disabled. |
| OD-S3-017 | Configuration success requires exact approved native target under versioned representation normalization; unknown/mismatch remain first-class. |
| OD-S3-018 | Generic same-object reentry is disabled pending Release/Pilot calibration; exact Compensation is the only initial bypass. |
| OD-S3-019 | Compensation is human-triggered, exact prior-Bid only, current-state-owned, freshly approved and never automatic rollback. |
| OD-S3-020 | Accepted Exception is exclusive governed risk acceptance: matching SLA may pause, risk remains visible, and no Command authority or coexistence is created. |
| OD-S3-021 | Internal SLO: Protection/Regression P95 <= 5m and hard <= 15m; other targeted updates hard <= 15m; full reconciliation at least hourly. |
| OD-S3-022 | Freshness authority is versioned by source/evidence kind, platform scope and decision purpose. |
| OD-S3-023 | Provider idempotency is Capability-profiled: native idempotency when verified; otherwise single-attempt default and Retry only on explicit verified `NOT_APPLIED`. |
| OD-S3-024 | Outcome baseline is versioned, action-bound and comparable; material confounder or insufficient history yields an inconclusive state. |
| OD-S3-025 | Dual-platform business capability is fully engineered; real Provider paths are independently evidence-gated; engineering closure is separate from Release. |
| OD-S3-026 | Outcome materiality uses a versioned, scoped threshold Policy with default-deny missing/conflict semantics. |
| OD-S3-027 | Optimization qualification uses a versioned, scoped, purpose-tiered Evidence Profile. |
| OD-S3-028 | Human response uses versioned lane-specific two-stage coverage-aware SLO Profiles. |
| OD-S3-029 | Target authority uses deterministic bounded exact candidate sets; Max CPC is the write-grade ceiling, not the automatic target. |
| OD-S3-030 | Write-grade conversion is versioned, stage-consistent and deterministically ad-linked; Provider conversion is observation-only. |
| OD-S3-031 | One Target Policy supports two explicit bases: `MAX_CPC_BOUNDED` or `CAUSE_BOUND_PROTECTION_STEP`. |
| OD-S3-032 | Affected-set action observation reservation permits only one active overlapping governed advertising intervention. |
| OD-S3-033 | Cause-verified Protection outcomes are distinct from primary efficiency success. |
| OD-S3-034 | Company total sales must pass and action-time frozen critical sales units have non-offsettable protection. |
| OD-S3-035 | Canonical evaluation uses complete evidence; Maker receives minimum authorized disclosure; endorser/approver receive responsibility-complete evidence. |
| OD-S3-036 | Manual configuration verification is graded: official machine evidence first, independent scoped human verification as fallback, never promoted to API evidence. |
| OD-S3-037 | Advertising profit Outcome is two-stage: 30-day Operational result followed by Settled confirmation. |
| OD-S3-038 | Regression quarantine is cause-proportional: affected object/set first, then all invalid shared-authority consumers or exact Capability scope. |
| OD-S3-039 | Provider transmission is the final external side-effect boundary; current quarantine/authority is revalidated immediately before sending. |
| OD-S3-040 | Canonical intra-lane priority is non-compensating, reason-coded and lexicographic. |
| OD-S3-041 | Emergency containment is asymmetric: multiple scoped actors may stop; cause-specific multi-party evidence and Owner approval are required to reenable. |
| OD-S3-042 | Approval uses a versioned scoped non-renewable Lease bounded by the earliest dependent authority expiry. |
| OD-S3-043 | Gate E requires a layered, scope-monotonic Pilot Evidence Bundle covering technical, workflow and early safety evidence. |
| OD-S3-044 | Pilot concurrency uses a versioned non-compensating multi-axis aggregate Exposure Envelope with reserved recovery headroom. |
| OD-S3-045 | Ordinary-route promotion is Direction/Candidate-Basis/Scope-specific and requires mature all-Material evidence; it remains per-command human approval. |
| OD-S3-046 | Interdependent decision policies activate as one scope-bound, whole-combination-validated Advertising Decision Policy Bundle. |
| OD-S3-047 | Live Queue, Daily Action Brief and Weekly Evidence Review share one Canonical Truth and distinct real-time/daily/weekly roles. |
## 21. Final Contract Gate consequence

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
local Git-mechanics approval. It pauses only under the Contract stop conditions.

The Controller's next engineering decision point is the one-shot independent
Deep Review over the exact published implementation candidate. Remote publication
and protected merge remain subject to their separately active authority and
repository Gates.

No acceptance of this Contract activates `RELEASE-V1-001`, Gate EV, Gate E,
Pilot, real Provider access, deployment or production write.

# SLICE-V1-003 detailed design

```yaml
document_type: evolvable_slice_detailed_design
slice: SLICE-V1-003
slice_title: Advertising & Traffic Efficiency
contract: docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md
contract_sha256: 1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c
contract_git_blob_sha1: 669c38dc4d9429249e663da0e684dabf570c4a4a
contract_bytes: 129400
contract_lines: 2687
owner_acceptance_evidence: docs/08-handoffs/OWNER-SLICE-V1-003-CONTRACT-ACCEPTANCE-EVIDENCE.md
owner_acceptance_evidence_sha256: d0532ff25806c5cbc96411aad81db8524671fba8b987a57a41843bff78bcce7d
source_protected_main: 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430
source_protected_main_tree: 0ca229112bcf351ab5c572dd8d375c647bab61c0
predecessor_slice: SLICE-V1-002
predecessor_snapshot_sha256: f4847d4fdca8bede97decc02a12f99b2358b196d3d5b31a3aac60362ae41799f
design_state: EVOLVING_WITH_IMPLEMENTATION
controlled_write_target: AD_BID_CHANGE
controlled_write_provider_paths: STRUCTURALLY_UNREACHABLE
production_write_enabled: false
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
```

This design describes the current implementation plan. It may change as code and
tests teach us more. It may not weaken or expand the accepted Contract. Where
this document and the Contract disagree, the Contract wins and this document is
the defect.

## 1. The problem in one paragraph

A seller pays a marketplace for traffic. The money leaves every day, the
marketplace reports how much of it converted according to its own attribution,
and the seller has no way to know whether any of it earned a profit — because the
platform's "conversion" is not the company's sale, the platform's "revenue" is
not net of commission, fulfillment, returns or COGS, and a bid that is
economically ruinous looks identical in the console to one that is printing
money. SLICE-V1-003 computes, for the smallest advertising object the platform
actually lets you control, the Advertising Contribution Profit that object
produced and the Contribution Profit it produced per official advertising rouble;
decides whether the honest state of that object is `PROTECTION`, `DATA_REPAIR`,
`OPTIMIZATION` or `WATCH`; ranks the work so that proven harm can never be
outranked by a large opportunity; routes it to one accountable person; and — only
where a platform capability has been independently verified for that exact
account and store, and only under a per-command human approval chain — lets a
bounded, exact, deterministic bid change leave the building. In this Slice no
bid change leaves the building at all: every Provider path is structurally
unreachable, and the evidence is fixtures.

## 2. The decisions everything else follows from

### 2.1 The atomic case is the object the platform lets you control, not the SKU you wish it were

Ozon and Wildberries expose advertising control at a level of their own choosing:
a campaign, an ad group, a target, a keyword. One such object routinely drives
several of our internal variants at once, and the platform offers no way to bid
on a share of it. So the atomic Advertising Case is:

```text
Organization
+ Platform
+ Marketplace Account / Store
+ minimum verified independently controllable native object (kind + native key)
+ native object lineage
+ native bidding mode
+ complete affected Internal Variant set (and its digest)
+ native Semantic Profile version
+ independent business cause
```

The affected set is stored as an ordered array of internal variant ids plus a
SHA-256 digest over that array. The digest is what freezes into a Preview, an
Approval, a Command and an Outcome Evaluation Plan; a variant appearing or
disappearing changes the digest, which invalidates every unexecuted decision
asset bound to the old one. SKU-level diagnostics are shown as *children* of the
case and are explicitly marked estimated where they rest on an allocation; they
never become separately executable. This is Contract §6.2 and invariants 3, 4, 5
and 11.

`ADVERTISING_OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE` is a first-class blocker,
not an absence. An object whose control granularity we cannot prove from official
evidence produces a Case that is visible, diagnosable and permanently
write-ineligible.

### 2.2 The platform's attribution is an observation; the company's sale is the truth

Two independent ledgers are carried side by side and never merged:

```text
provider-attributed : impressions, clicks, spend, provider orders, provider revenue
company-canonical   : Order → Completed Sale → Retained Sale → Settled Sale
```

Provider attribution supports reconciliation, trend diagnosis and discrepancy
work. It cannot become canonical conversion, canonical profit, or an outcome. A
material gap between the two — measured, versioned and thresholded by policy —
is itself a `DATA_REPAIR` cause and fails closed for write-grade Max CPC.

The forbidden shortcut is named and tested: company total sales divided by
advertising clicks is not a conversion rate. `AdLinkedConversion` refuses to be
constructed from a numerator that is not deterministically linked to the same
atomic object, window and affected set.

### 2.3 Two profit axes that cannot rescue each other

Efficiency is a non-compensating Pareto rule over two axes:

```text
absolute Advertising Contribution Profit
Contribution Profit per official advertising RUB
```

Success requires one axis to improve materially, the other not to worsen
materially, and sales preservation to pass. A loss that shrinks is
`IMPROVED_NOT_HEALTHY` — a real and reportable result, and never `HEALTHY`. The
Java type that carries this is `DualAxisVerdict`, whose constructor refuses to
produce a healthy verdict from a negative absolute result. Contract §6.6,
invariants 20 and 21.

### 2.4 Sales preservation is a conjunction with a non-offsettable term

```text
complete affected-set company total passes
AND every action-time frozen required critical sales unit passes
```

Critical sales units are chosen *before* the action by the applicable Outcome
Policy version and frozen into the Evaluation Plan. Growth elsewhere cannot
offset a required unit's failure, and missing evidence for a required unit
produces `OUTCOME_UNRESOLVED`, never a pass borrowed from a healthy sibling.
`SalesPreservation.evaluate` returns a verdict value carrying the per-unit
results; there is no scalar it can be collapsed into.

### 2.5 Freshness is a function of three things, and absence blocks only what consumes it

A single `FRESHNESS_PROFILE` is keyed by

```text
evidence kind × platform/account/store/semantic-profile scope × decision purpose
```

with ten purposes from `QUEUE_OBSERVATION` to `SETTLED_FINANCIAL_OUTCOME`. Two
rules make it more than a TTL. First, a newly *ingested* old fact is not fresh —
the profile reads source time, accepted time, window completeness, expected
publication lag and known correction window, so back-filling last month does not
make last month current. Second, a mature 30-day cohort is not stale merely
because its business period is old — the outcome purposes judge maturity, not
recency. Monotonicity is enforced structurally: `DecisionPurpose` declares a
partial order and `FreshnessProfileSet` refuses activation if a write purpose is
weaker than the recommendation purpose that feeds it.

A missing or conflicted profile blocks exactly the purposes that consume it. It
cannot erase an independent Fresh danger fact — which is why Protection can
proceed on one-sided proof while Optimization cannot.

### 2.6 Unknown has no direction

Increase requires complete write-grade proof. Protection may proceed on a Fresh
one-sided danger proof, but only when the missing fact *cannot reverse the
dangerous direction* — and that condition is an explicit, reason-coded predicate
(`OneSidedDangerProof`), not a default. Unknown alone produces neither a
decrease, nor an increase, nor a pause, nor a success. This asymmetry is Contract
§6.9, OD-S3-009, invariants 35–37, and it is why the lane resolver is a
short-circuit ladder whose order is load-bearing and tested by enumeration.

### 2.7 The rank cannot be bought

Priority is lane-first, then a versioned non-compensating lexicographic order
inside the lane. Protection carries four hard sub-tiers (`P0` regression/
quarantine/integrity, `P1` sellability or critical-sales danger, `P2` proven
continuing economic harm, `P3` other qualified danger) and the intra-tier order
is a fixed sequence of reason-coded comparators, not a weighted sum. A commercial
score may be *displayed*; it is computed inside a band so that it can never reach
across a lane or a sub-tier boundary — the same clamp-and-band device SLICE-V1-002
proved, widened to carry the sub-tier. AI cannot compute or override either.

### 2.8 One cause, one case, one task — and a page view is not an acknowledgement

The dedup key is the *cause*, not the calculation. Repeated recalculation of one
cause updates one Case and one active Task. Human response is a two-stage
coverage-aware profile: `ACKNOWLEDGEMENT` then
`ACTION_DECISION_OR_FIRST_ATTRIBUTABLE_ACTION`. Opening a page is neither.
Outside staffed coverage the staffed clock may pause, but wall-clock exposure
age, continuing spend and known exposure stay visible and the case records
`OUT_OF_COVERAGE_ACTIVE_HARM`. An SLO breach escalates; it never authorizes a
write.

### 2.9 An accepted exception disposes of risk without changing the facts

`ACTION_REQUIRED`, `ACTION_IN_PROGRESS` and `ACCEPTED_EXCEPTION_ACTIVE` are
mutually exclusive dispositions over a Case whose calculated lane is untouched.
A valid exception pauses only the matching ordinary Action SLO and suppresses
duplicate notification. It cannot make the case healthy, become a guardrail pass,
authorize any bid direction, or coexist with an active command for the same
cause. Expiry, scope drift, exposure growth, affected-set change, new regression,
evidence conflict, authority loss or a governing-bundle change reopens the same
case. There is no renewal and no silent extension. This reuses the SLICE-V1-002
`operationsworkflow` exception machinery rather than growing a second one.

### 2.10 The manual path is complete, and it is not a hidden API

Both platforms get a full Governed Manual Shadow: an exact Manual Execution
Packet binding object, current observed configuration, exact intended target,
complete affected set, evidence, guardrails, approvers, expiry, expected impact,
verification plan and Bundle identity. It may carry Bid, Budget, Pause/Resume or
another modelled action for which we have a deterministic recommendation and the
actor has legal platform authority — because a human logging into the seller
console is a lawful operation and an API call we have not verified is not.

Configuration evidence is *graded*, and the grades never promote:

```text
1 verified official API Readback
2 verified replayable official configuration export
3 independent scope-authorized manual verification   (verifier ≠ executor)
4 executor self-report only                          → ACTION_REPORTED_CONFIGURATION_UNVERIFIED
```

Grade 3 proves only the console state actually observed. It never proves API
idempotency, exact application time, or provider acceptance semantics. A
confirmed *or uncertain* manual action consumes the same affected-set reservation
and the same aggregate exposure as a controlled write, because the real
advertising environment changed either way.

### 2.11 `AD_BID_CHANGE` is the only controlled write, and its target is chosen before anyone approves it

One capability. Three separately gated directions: `PROTECTION_DECREASE`,
`OPTIMIZATION_INCREASE`, `EXACT_PRIOR_BID_COMPENSATION`. No budget, no
pause/resume, no strategy or mode switch, no structural edit.

Targets come from one versioned `AD_BID_TARGET_POLICY` producing a *finite
deterministic candidate set*. The operator selects or rejects a generated
candidate, chooses the manual path, or asks for policy review. The operator
cannot free-type a value. Two candidate bases exist and are labelled on every
Preview:

- `MAX_CPC_BOUNDED` — a write-grade Max CPC exists. Increase may not exceed the
  conservative provider-valid ceiling below it. Protection treats the ceiling as
  an economic reference, not an automatic target.
- `CAUSE_BOUND_PROTECTION_STEP` — no write-grade Max CPC exists. One bounded
  lower candidate may be generated under eight simultaneous conditions, and the
  Preview must state in terms that cannot be styled away that the target limits
  exposure and proves nothing about optimality, profitability or health.

Provider normalization and valid-step conversion happen *before* Preview and
Approval. Adapter runtime rounding is prohibited and is refused in the adapter,
in the SQL that opens an attempt, and by an architecture test.

### 2.12 Every initial nonzero bid change is material

The initial ordinary nonzero envelope is zero. Materiality is a versioned
multi-axis non-compensating policy; any hard trigger suffices and low exposure on
another axis cannot offset it. The initial route is

```text
scoped Marketplace Operator (Maker)
→ distinct Operations Lead (operational endorsement)
→ Human Owner (final per-command approval)
```

`ORDINARY_IMPACT`, `MATERIAL_IMPACT` and `MATERIALITY_UNRESOLVED` are all
implemented, and the promoted Ordinary route is implemented as a structurally
supported but evidence-gated path whose promotion record does not exist and
cannot be created by this Slice. Ordinary, when it exists, is still per-command
Maker-Checker. It is never standing automation.

### 2.13 Provider acceptance is not success; a third value is never overwritten

The command lifecycle carries fifteen distinguishable outcomes and refuses to
collapse them. Success requires the official current bid to equal the exact
approved native target after *only* the pre-approved versioned representation
normalization. A tolerance, a nearest value or an economic substitute is not a
match. A third value — anything that is neither the target nor the captured prior
— is never overwritten; it enters `LATER_CHANGE_OR_MISMATCH_INVESTIGATION`.

Retry is capability-profiled. With verified provider-native idempotency, the same
command may retry within a bounded budget after resolving status/readback first.
Without it, the default is one provider submission, and a retry requires official,
replayable evidence that the first request was *not accepted or was terminated
before application*, that the current bid still equals the captured prior bid,
that the error is documented-retryable, and that every authority is still valid.
A timeout plus "the old bid is still there" is not that evidence, and the SQL
that completes an attempt upgrades it to `UNKNOWN_STATE` rather than letting the
adapter's optimism through.

### 2.14 Compensation is a human decision to restore an exact number

There is no automatic rollback and no automatic compensation. Generic same-object
reentry is disabled. The single initial bypass is an exact prior-bid
Compensation inside the original action lineage, eligible only when the prior
readback matched, a stop condition or regression is satisfied, the official bid
*still equals* what this command wrote, the captured prior bid is available, no
later external change owns the current state, all hard gates pass, a fresh
Preview exists, and a fresh Maker → Operations → Owner chain approves it. Its
target is the captured prior bid and nothing else. Its readback is not a business
outcome.

### 2.15 One active intervention per affected set, inside a multi-axis envelope

An active governed intervention — controlled command, confirmed manual packet, or
compensation — reserves the complete affected set. An overlapping new action is
blocked; Protection and Regression take deterministic precedence over
Optimization. Release requires resolved configuration, no Unknown or Mismatch,
the first required early Completed-Sales observation, and no unresolved
action-associated regression. It does not wait for 30-day Retained Sales unless
the active gate says so.

Above that sits a versioned non-compensating aggregate Exposure Envelope with six
independent axes, including reserved recovery headroom that ordinary actions
cannot borrow. Every hard axis must pass; Protection decrease cannot net against
Optimization increase; Unknown and Mismatch keep consuming capacity until they
are factually resolved. Capacity is rechecked immediately before transmission —
an approval is not a reservation of future capacity, and waiting never extends
a lease.

### 2.16 The transmission boundary is where containment becomes real

Quarantine and Kill take effect at an authoritative instant and stop any *new*
external side effect from beginning. Unused approvals are invalidated; a PENDING
command with no provider call is terminated without one; a LEASED-but-unsent
command is blocked by live pre-transmission revalidation; an unexecuted packet is
revoked. A request whose transmission already began remains a possible external
fact: it may query status, read back and reconcile, and it may not retry under
the old approval even if the provider later proves `NOT_APPLIED`.

Stopping is fast, scoped and available to several roles. Restarting is none of
those: no actor who can stop may unilaterally restart, and reenablement requires
classified root cause, resolved unknowns, replaced authorities, reconciled
results, current capability evidence, an exact new scope, Operations endorsement,
Owner approval, and a security attestation when the cause was technical. Old
decision assets never resurrect.

### 2.17 The outcome has two stages and the later one is allowed to contradict the earlier

An Operational verdict over 30-day Retained Sales and eligible Operational
Contribution Profit is always visibly `OPERATIONAL_NOT_SETTLED`. When settlement,
return loss, fees, spend corrections and adjustments mature, the *same* lineage
and the *same frozen* Outcome Policy version produce a Settled verdict against a
stage-consistent Settled baseline. A settled contradiction preserves the
historical Operational version, reopens or escalates the same lineage, removes
the action from settled-confirmed reuse, and creates a Finance review
responsibility. A favourable settlement may likewise upgrade an earlier
no-improvement result. Settlement attribution gaps are never filled by invented
allocation.

Cause-specific protection outcomes — `VERIFIED_AD_RISK_CLEARED`,
`VERIFIED_AD_EXPOSURE_STOPPED` — are separate results with their own evidence
requirements, and neither implies inventory repair, sales preservation or
efficiency success.

### 2.18 Interdependent policies activate together or not at all

Twelve versioned authorities govern a production decision scope. Publishing a new
version of any one of them changes nothing about production write authority. What
changes authority is the atomic activation of an
`ADVERTISING_DECISION_POLICY_BUNDLE` that *references without re-owning* an exact
version of each, bound to an exact organization/platform/account/capability/
direction/candidate-basis/object-kind/lifecycle scope and effective period.

Activation is refused unless whole-combination validation proves every referenced
version valid, conversion and Allowable CPA stages consistent, target candidates
provider-valid, purpose monotonicity intact, materiality/ordinary/exposure/lease
rules coherent, provider and readback semantics valid, required fixture and
shadow tests passing, and the bundle scope no broader than the Release/Gate
evidence. No unique complete active bundle means the write path fails closed.
Rollback is a new audited version; accepted history is never mutated.

### 2.19 One truth, three cadences

The Live Queue is the current work authority. The Daily Action Brief and Weekly
Evidence Review are versioned, `as_of`-stamped, read-only projections of the same
canonical authorities. Late data never overwrites a published artifact; it
produces a revision with delta lineage that preserves the original `as_of`,
source cutoff, calculation versions, bundle versions and gaps.

### 2.20 Targeted and full reconciliation are the same calculation

A qualifying accepted, corrected, invalidated, expired or matured fact enqueues a
targeted recalculation of the exact affected cases. An hourly full reconciliation
repairs missed triggers, late and out-of-order facts, expired authority, stale
decision assets and unresolved recalculable outcomes. For identical as-of facts,
authority versions and scope the two produce the same result — structurally,
because both enter the same single `@Transactional` refresh method over the same
pure calculator, exactly as SLICE-V1-002 established.

### 2.21 No provider call happens in this Slice

`production_write_enabled` stays `false` and fails startup if set true. No
verified `ad-bid-change` capability, operation, api-profile, auth-header or
account-bound verification row is seeded. The adapter refuses before it opens a
socket when any of the five verified facts is missing; the SQL that opens an
attempt refuses when the operation snapshot is null; the registry writer trigger
prevents the application role from ever writing a verified registry row. The
Ozon and Wildberries Semantic and Capability Profiles shipped here are explicitly
labelled synthetic fixtures and are `UNVERIFIED`. Ozon evidence never authorizes
Wildberries and Wildberries evidence never authorizes Ozon.

## 3. Module shape

A new Spring Modulith module, `advertisingefficiency`, owns the vertical. It is
the advertising analogue of `availabilityrisk` and follows the identical layout.

```text
com.mimococo.marketops.advertisingefficiency/            (module API — published types only)
    AdvertisingCaseView, AdvertisingChildView, AdvertisingRankFactorView,
    AdvertisingEvidenceView, AdvertisingOutcomeView, AdvertisingCaseQuery,
    AdvertisingLane, AdvertisingCause, AdEvidenceState, AdConfidence,
    ProfitAxis, CandidateBasis, DecisionPurpose, AdObjectKind, AdChildKind
  internal/domain/          pure values + static calculators; no Spring, no IO
  internal/application/     @Service orchestration; owns transactions
  internal/infrastructure/jdbc/  @Repository, JdbcClient, raw SQL
  internal/config/          @ConfigurationProperties + @Configuration
  internal/web/             package-private @RestController @ConsoleApi
```

Every published view uses `String` for enum-valued fields, as `availabilityrisk`
does, so no enum crosses the JSON boundary.

The module must be added to three places that would otherwise fail the build:

1. `ModulithArchitectureTest.declaredModulesAreTheOnesDetected` — the exact
   detected-module list.
2. `adminobservability.audit.AuditSourceDomain` — new constant
   `ADVERTISING_EFFICIENCY` with `dbValue()` `advertisingefficiency`.
3. `ops.metadata_audit_event_source_domain_ck` — widened by forward migration,
   following the V0011/V0030 drop-and-add pattern.

Existing modules are extended, not duplicated:

| Concern | Owner | Extension |
| --- | --- | --- |
| Native ad object, spend, traffic, attribution facts | `marketplaceintegration` + `operatingfacts` | new official-fact tables and normalization; existing Raw custody |
| Provider capability, credential ref, invocation, Readback | `marketplaceintegration` | `ad-bid-change` capability, `AdBidWritePort`, ad command stack |
| Canonical ad metrics, conversion, Allowable CPA, Max CPC | `analyticsdecision` | new metric definitions and calculators |
| Freshness / qualification / target / outcome / priority / SLO / lease / exposure policy | `operationsworkflow` | new versioned policy authorities |
| Recommendation, Task, Approval, Accepted Exception | `operationsworkflow` | `AD_BID_CHANGE` action kind, ad case intake |
| Bundle activation | `operationsworkflow` | cross-authority activation record |
| Audit | `adminobservability` | new source domain only |
| AI explanation | `aicopilot` | new projection version; non-canonical |

No second ingestion, metric, policy, workflow, command, readback, bundle or audit
authority is created. This is Contract invariant 134 and is proved by an
architecture test and by a whole-tree marker scan of the kind
`AvailabilityNonGoalsTest` established.

## 4. The controlled-write stack

### 4.1 Why a sibling table set and not a rewrite

`ops.price_command` and its nine SECURITY DEFINER functions are an applied,
immutable migration. The Contract forbids editing applied migrations and requires
forward-only change. The price stack is also price-*shaped* in ways an ad bid is
not: it keys liveness on `platform_listing_variant_id`, it carries
`prior_price`/`target_price` bound to `core.listing_price_observation`, and its
authority snapshot is built entirely from listing/price/mapping/commercial-policy
joins that have no advertising analogue.

So `V0036` creates a sibling set — `ops.ad_bid_command`,
`ops.ad_bid_command_transition`, `ops.ad_bid_command_attempt`,
`ops.ad_bid_command_readback`, `raw.ad_bid_response_observation` — inside the
same module, under the same rules, with the same privilege posture (application
role holds `SELECT` only; every transition is a SECURITY DEFINER function; the
transition graph is rows, not code). That is one execution authority with two
capabilities, not two authorities. The generic scaffolding that *can* be shared
without editing applied SQL — the outcome vocabulary, the lease/fence discipline,
the evidence-binding rule, the worker skeleton — is shared in Java.

### 4.2 What the forward migrations widen

Closed vocabularies that must admit the new capability are widened by
`DROP CONSTRAINT` / `ADD CONSTRAINT` in a forward migration, never by editing an
applied file:

```text
ops.recommendation_action_ck              + AD_BID_CHANGE
ops.policy_authorization action_kind      + AD_BID_CHANGE
ops.pilot_allowlist_entry_action_kind_ck  + AD_BID_CHANGE
platform.platform_endpoint operation_function + AD_BID_APPLY / AD_BID_STATUS
                                              / AD_BID_READBACK / AD_BID_RESTORE
platform.request_template_is_well_formed  + nativeCampaignKey, nativeObjectKey,
                                            targetBid, bidUnitCode
platform.capability_operation_matches_write_model  capability-code dispatch
ops.metadata_audit_event_source_domain_ck + advertisingefficiency
```

`capability_operation_template_ck` currently requires `%{targetPrice}%` for
`APPLY`/`RESTORE`. It is widened to require the placeholder that matches the
capability's own write vocabulary, so a price operation still requires
`{targetPrice}` and an ad-bid operation requires `{targetBid}` — the constraint
becomes more specific, not weaker.

### 4.3 Error-code block

The next free contiguous SQLSTATE block is taken: **MO090–MO099**.

```text
MO090 AD_BID_COMMAND_AUTHORITY_LOST
MO091 AD_BID_COMMAND_TRANSITION_NOT_ALLOWED
MO092 AD_BID_COMMAND_WRITE_GATE_CLOSED
MO093 AD_BID_COMMAND_SUCCESS_WITHOUT_READBACK
MO094 AD_BID_COMMAND_COMPENSATION_UNSAFE
MO095 AD_BID_COMMAND_LEASE_INVALID
MO096 AD_BID_COMMAND_ATTEMPT_ALREADY_COMPLETED
MO097 AD_BID_RESERVATION_CONFLICT
MO098 AD_BID_AGGREGATE_ENVELOPE_BLOCKED
MO099 ADVERTISING_POLICY_BUNDLE_INVALID
```

### 4.4 The write gate

`ops.evaluate_ad_bid_write_gate(uuid) RETURNS text[]` returns an empty array only
when every one of the following holds; any other result is a refusal carrying its
reason codes:

```text
COMMAND_NOT_FOUND                     COMMAND_AUTHORITY_MISMATCH
CAPABILITY_NOT_VERIFIED               CAPABILITY_NOT_AVAILABLE_FOR_STORE
CAPABILITY_SWITCH_DISABLED            GLOBAL_SWITCH_DISABLED
SCOPED_SWITCH_DISABLED                ENTITY_NOT_ALLOWLISTED
AUTHORIZATION_INVALID_OR_EXPIRED      RECOMMENDATION_STALE
AFFECTED_SET_DIGEST_CHANGED           MAPPING_UNRESOLVED
MAPPING_CONFLICT_OPEN                 GUARDRAIL_NOT_PASSED
BUNDLE_UNRESOLVED                     BUNDLE_SCOPE_EXCEEDED
RESERVATION_CONFLICT                  AGGREGATE_ENVELOPE_BLOCKED
QUARANTINE_ACTIVE                     KILL_SWITCH_ACTIVE
APPROVAL_LEASE_EXPIRED                CANDIDATE_BASIS_NOT_ENABLED
DIRECTION_NOT_ENABLED                 ORDINARY_ROUTE_NOT_PROMOTED
```

It is evaluated at lease, at compensation lease, and again inside
`ops.open_ad_bid_command_attempt` for `APPLY` and `RESTORE` — the last of these
being the transmission boundary of Contract §6.28 and OD-S3-039.

The feature flag code is `ad-bid-change-write`, distinct from
`price-change-write`, at `GLOBAL` and `CAPABILITY` scope with the same
"missing = off" semantics.

## 5. Shared-Spine extensions

Kept to the minimum the Contract requires.

- `ErrorCode` gains the refusals the advertising surfaces need and the console
  status map must place them, so nothing falls to the `422` default by accident:
  `ADVERTISING_EVIDENCE_INSUFFICIENT`, `AFFECTED_SET_UNRESOLVED`,
  `RESERVATION_CONFLICT`, `AGGREGATE_ENVELOPE_BLOCKED`, `POLICY_BUNDLE_UNRESOLVED`,
  `APPROVAL_EVIDENCE_SCOPE_BLOCKED`, `CANDIDATE_NOT_IN_SET`,
  `MANUAL_VERIFICATION_INSUFFICIENT`, `QUARANTINE_ACTIVE`.
- `identityaccess` gains six action scopes, seeded by migration and mirrored in
  `ActionScopeCode`:
  `ADVERTISING_VIEW` (no step-up), `ADVERTISING_TASK_ACT` (no step-up),
  `ADVERTISING_EXCEPTION_REQUEST` (no step-up),
  `AD_BID_CHANGE_ENDORSE` (step-up), `AD_BID_CHANGE_APPROVE` (step-up),
  `ADVERTISING_POLICY_MANAGE` (step-up).
  No new role is invented: the Contract's `PLATFORM_ADMIN` maps onto the existing
  security/kill authority (`KILL_SWITCH_OPERATE` held by `OWNER`), and the
  decision owners are the existing `OPS_LEAD`, `MARKETPLACE_OPERATOR`, `OWNER`,
  `FINANCE_ANALYST`, `RISK_AUTHORITY` and `AUDITOR`.
- `Money` is used unchanged for every monetary quantity. A bid is money with an
  explicit currency and a provider-declared unit and step; the unit and step live
  in the Semantic Profile, never in the adapter.

## 6. Schema, V0036 onward

Planned forward migrations, each self-contained, each registering every new table
in `platform.control_route_inventory` and granting per object:

| Migration | Contents |
| --- | --- |
| `V0036` | *(landed)* advertising identity and official facts: semantic profile, native object registry with lineage and proven control granularity, structural relationships, append-only affected set + digest, observed configuration, official object spend/traffic/attribution with correction lineage, explicitly-labelled per-listing allocation. Also carries the six IAM action scopes, the role matrix and the `advertisingefficiency` audit source domain, because every later migration and every Java class depends on them |
| `V0037` | *(landed)* canonical advertising metrics and decision evidence: conversion definition, stage-bound Allowable CPA definition, deterministic ad-linked sale events, purpose-specific freshness profiles, purpose-tiered optimization qualification, and the two monotonicity functions the Bundle validation calls |
| `V0038` | advertising case projection, lanes, priority policy, rank factors, evidence, recalculation queue, reconciliation run, SLO and trace events |
| `V0039` | accountable task, exception, manual execution packet, configuration verification, approval lease, materiality and target policies |
| `V0040` | `AD_BID_CHANGE` command, transition graph, attempt, readback, raw response custody, write gate, lease/transition/attempt/readback functions, compensation guard |
| `V0041` | reservation, aggregate exposure envelope, quarantine, kill/reenablement, decision policy bundle and its whole-combination validation |
| `V0042` | outcome evaluation plan, baseline and confounder authority, operational and settled outcome lineage, regression, report projections |
| `V0043` | remaining vocabulary widenings and the synthetic Ozon/WB fixture profiles marked `UNVERIFIED` (IAM and the audit domain moved forward into `V0036`) |

### 6.1 As built

The split above was the plan. What landed differs, and the differences are
recorded here rather than by editing the plan, because the reasons are the
useful part:

| Migration | As built |
| --- | --- |
| `V0036`–`V0039` | as planned |
| `V0040` | *not* the command. Widening the **existing** write registry so it can describe an advertising capability, rather than growing a second registry beside the price one. One execution authority was worth more than a clean migration boundary |
| `V0041` | reservation, exposure envelope, containment, decision policy bundle |
| `V0042` | the `AD_BID_CHANGE` command, its sixteen states and thirty-three edges, attempt, readback, raw custody, authority snapshot and the write gate's twenty-two reason codes |
| `V0043` | attempt lifecycle, readback derivation and the write-path evidence functions |
| `V0044` | case supersession. Found by a flow test that produced two cases after a cause changed: without it a cause that stopped holding left its case in the queue forever |
| `V0045` | `ops.create_ad_bid_command`, the only way a command comes into existence |
| `V0046` | `ops.capture_ad_bid_authority_snapshot`. The guardrail needed the advertising authority and its instant together; reading them separately leaves a window in which a fact can move unnoticed |
| `V0047` | the parameter contract refuses a target bid of zero. Found by the Java/SQL parity test: the pattern admitted `'0'`, and a zero bid withdraws an object from auction, which is a status change this product does not write |
| `V0048` | taking a reservation becomes one serialized statement and the application role loses `INSERT`/`UPDATE`. Check-then-insert left a window in which two interventions could hold the same product variants |
| `V0049` | the frozen Outcome Evaluation Plan, the operational and settled lineage, and the early Completed-Sales Guard as a check constraint |

The constraints that did not move: forward-only, contiguous
`V%04d__[a-z0-9_]+.sql`, no edit to V0001–V0035, every new table in the route
inventory with a stated reason, no `DELETE` grant anywhere, and every new
vocabulary as `text` + named `CHECK`.

### 6.2 Module dependency direction

`operationsworkflow` owns approval and execution and knows nothing about bids;
`advertisingefficiency` knows what a bid decision consists of and nothing about
approving one. The interfaces that join them — `AdvertisingDecisionAuthority`,
`AdvertisingRecommendationIntake` and their shapes — are therefore **owned by
`operationsworkflow`** and implemented by `advertisingefficiency`, the same way
`AvailabilityCaseIntake` already works.

The first attempt had them the other way round and produced a cycle:
`advertisingefficiency → availabilityrisk → operationsworkflow →
advertisingefficiency`. The boundary rules caught it. The direction matters
beyond that cycle: the workflow is what every other module already depends on,
so an interface it consumes must not be owned elsewhere.

### 6.3 Two copies of one rule, and the test that keeps them equal

The bid-change parameter contract exists in Java and in SQL. Both are needed —
the database refuses a row no Java touched, and the Java refuses before a
transaction opens so an operator gets a reason rather than a constraint
violation. Two copies drift, so `AdBidParameterContractParityIT` puts one shared
case list to both implementations and fails on any disagreement. It has already
paid for itself once, in `V0047`.

## 7. Frontend

One new console surface, `Advertising Control`, matching the existing idiom
(React 19 + Vite, no router library beyond the current shell, `consoleApi`
client, package-private-equivalent component boundaries).

The state vocabulary is the part that carries the Contract. Ten visually and
programmatically distinct states must never be collapsed:

```text
observed · canonical · estimated · stale · blocked
unverified · operational · settled · unknown · quarantined
```

They are rendered by one `EvidenceChip` primitive driven by a total mapping from
the API's `String` state, so an unmapped state is a build failure rather than a
silently neutral badge. Role-specific minimum disclosure is enforced in the
backend, and the console renders exactly what it receives; a field the API did
not send is rendered as "not disclosed", which is visually distinct from a data
gap. Russian text, UTF-8 and store-local time and currency display are handled by
the store's declared timezone and currency, never the browser's.

## 8. What this design does not do

It does not implement, and its tests prove the absence of: `STOCK_CHANGE`,
replenishment quantity or order date, Overstock/Slow-moving/Dead Stock,
Allocation or Transfer, budget writes, campaign pause/resume, strategy or mode
switches, campaign/target/keyword creation or deletion, negative keywords, search
terms, creative or content writes, promotion or listing writes, portfolio
reallocation or cross-action netting, standing policy automation, generic
same-object reentry, predictive or reinforcement-learning bid control, browser
automation or scraping, any new PII or secret purpose, external AI production
invocation, or any real Ozon or Wildberries call.

It does not activate `RELEASE-V1-001`, Gate EV, Gate E, Pilot, deployment or
production write, and merge will not either.

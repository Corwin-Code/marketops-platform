# SLICE-V1-002 detailed design

```yaml
document_type: evolvable_slice_detailed_design
slice: SLICE-V1-002
slice_title: Stockout & Availability Risk with Accountable Response
contract: docs/03-work-items/SLICE-V1-002-stockout-availability-risk-and-accountable-response.md
contract_sha256: d89ea296d0ff854c7d57895b448f9467a22106881d26de4c62a0e8629600556e
contract_git_blob_sha1: 1caa50f1b33011f7d226c83654835401c00bde1e
owner_acceptance_evidence: docs/08-handoffs/OWNER-SLICE-V1-002-CONTRACT-ACCEPTANCE-EVIDENCE.md
owner_acceptance_evidence_sha256: 4e243c85412c549975ef70ee46bb09502a3157c0d4bb6a1b2679b7745b96538e
source_protected_main: 8a7076877374391cf851481c023dfb0e621ab712
source_protected_main_tree: b87ec67d0242eb86e15698ab95430c37f0fe4328
root_cause_rework_source_head: c5d896a4ca01ecdc6d4add85fb4fd2e33ba8e4c6
root_cause_rework_source_tree: c94341232b5fa67b5c40a1e6be121a7696e748c4
frozen_finding_set_sha256: 60589cfa9303d17e71910e085fd18f1d68b87dd9e3b56a99bf6f799879ebcf94
design_state: EVOLVING_WITH_IMPLEMENTATION
controlled_write_target: NONE_IN_THIS_SLICE
production_write_enabled: false
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
```

This design describes the current implementation plan. It may change as code and
tests teach us more. It may not weaken or expand the accepted Contract.

## 1. The problem in one paragraph

A merchant loses money when a profitable SKU stops being buyable. That happens in
two independent ways, and the product must never let one hide the other. A
*channel* can be unavailable while the warehouse is full — a listing is blocked,
a fulfillment mode has run dry, a store is out of sync. A *company* can be about
to run out of the goods themselves, which no channel fix repairs. SLICE-V1-002
calculates both, ranks them by what is actually at stake, turns the ones that
warrant action into one accountable Task with a named owner, verifies afterwards
whether the business risk actually improved, and reopens the same case when it
did not. It writes nothing to any marketplace.

## 2. The decisions everything else follows from

### 2.1 Two children, one card, neither able to clear the other

The parent card is `Organization + Internal Product Variant`. It carries two
independently governed child risks:

```text
channel child : Platform + Marketplace Account/Store + Listing Variant + Fulfillment Mode
company child : Organization + Internal Product Variant
```

They are computed by different code paths from different evidence, persisted as
separate rows with separate lanes, evidence states, confidences and causes, and
they clear separately. The parent shows the most severe eligible child lane and
always names which child produced it. This is Contract invariants 2, 3, 4 and
16, and it is the reason a stale internal-stock feed cannot silence a Fresh
`Ozon FBO available = 0`.

### 2.2 Asymmetric safety: the channel may be confident, the company may not

Channel risk is a statement about one exact observed listing/mode. When that
observation is Fresh, it is actionable on its own, and unrelated defects
elsewhere are irrelevant to it.

Company risk is a statement about everything the company owns. It therefore
fails closed. The company child can only reach a safe lane when every material
input needed to establish supply and demand is present, fresh, unconflicted,
mapped and safely deduplicated. When something material is missing, exactly one
of two things happens:

```text
known Fresh deduplicated lower-bound evidence already proves danger
  -> PROVISIONAL_RISK + DATA_BLOCKED, carrying its conservative proof

the missing fact determines the answer
  -> COMPANY_RISK_UNRESOLVED + DATA_BLOCKED
```

There is no third branch, and neither is ever rendered as `HEALTHY`.

### 2.3 A conservative proof is a value, not a narrative

`PROVISIONAL_RISK` is only emitted when the calculator can construct a
reproducible lower-bound argument: using only evidence that is Fresh, owned and
deduplicated, the best case still runs out inside the coverage horizon. The
proof is persisted as structured terms (which supply counted, which was excluded
and why, which demand rate, which horizon), so a reviewer can recompute it and a
test can assert it. If no such proof exists, the answer is `UNRESOLVED`.

### 2.4 Supply is proven distinct before it is added

Internal warehouse stock and marketplace-visible stock are never summed on
faith. A `supply_ownership_declaration` states, per
`(organization, store, fulfillment_mode)`, whether the platform-visible quantity
is `MIRRORS_INTERNAL` (the same physical units, therefore not additive),
`PHYSICALLY_DISTINCT` (company-owned stock actually held at the platform,
therefore additive) or absent. Absent or conflicting means `UNKNOWN`, and
`UNKNOWN` cannot produce a company-safe result. This is what makes
invariants 5, 6 and Contract `S2-AC-018`/`019`/`020` testable rather than
aspirational.

### 2.5 Inbound is a time-phased attestation, not a number someone typed

An inbound record only reduces company risk when a role-scoped Product /
Procurement actor bound attributable evidence to it, its business status is one
that the policy accepts, its expected-arrival window falls inside the coverage
horizon, and it is still Fresh and unconflicted. Draft and buyer estimates are
visible and count for nothing. Amendment and cancellation append a new version
rather than editing the old one, and every version transition triggers
recalculation. Inbound never becomes current on-hand.

### 2.6 Every policy is versioned, effective-dated and fails closed

Lead-time/safety, demand-window selection, work activation, queue priority,
return quality, supply ownership and exception materiality are all versioned,
effective-dated authorities resolved by exact scope. Missing, expired,
overlapping or conflicting policy never degrades to zero or an implementation
default; the affected decision blocks. Effective periods are enforced by
PostgreSQL `EXCLUDE USING gist` no-overlap constraints on active rows, the same
mechanism `core.cost_version` already uses, so two overlapping active versions
cannot exist even under concurrency. Publication, retirement and cancellation
are attributable, audited and enqueue the affected portfolio for recalculation.

### 2.7 Zero sales is not evidence of zero demand

Every demand window carries observation coverage: was the listing sellable, was
there stock to sell, was the source fresh, was there a known outage. A window
that was materially censored cannot lower canonical demand. The deterministic
policy selects from eligible windows; when all recent windows are censored the
last eligible demand may be carried forward for a bounded, versioned period with
a visible confidence downgrade and its source period on the card. When that
period expires the answer becomes `DEMAND_CENSORED`/`DATA_BLOCKED` — never zero,
and never an imputed model estimate.

### 2.8 One cause, one case

Task identity is the *cause*, not the calculation. A cause key is
`(organization, card, child identity, cause code)`. Repeated recalculation of
the same cause updates one case, appends evidence and may change severity, due
time or assignee under governed policy; it never creates a second task. A
database partial unique index on the active cause key makes that true under
concurrency and replay, not just in the happy path. Different independently
actionable causes with different owners get separate, explicitly related tasks.

### 2.9 Action is not outcome

Closing a task requires two distinct stages. Stage 1 records a structured,
attributable action with evidence — a free-text acknowledgement is rejected at
the type level, not by a validator that can be skipped. Stage 2 is a *fresh
cause-specific* observation that the business risk actually improved. Only
stage 2 produces `VERIFIED_SUCCESS`. Failure, ETA slip, evidence expiry or
risk regression reopens or escalates the *same* case with its history intact.
Action SLA and Outcome SLA are separately recorded and separately observable.

### 2.10 An accepted exception disposes of the risk without changing it

An exception never rewrites the calculated lane and never produces
`VERIFIED_SUCCESS`. The card shows the calculated risk *and* the
`ACCEPTED_EXCEPTION` disposition *and* its expiry. Approval authority is
proportional to lane, recurrence and materiality; for CRITICAL, repeated or
material cases the requester cannot be the sole final approver. Missing,
expired, overlapping or conflicting authority produces
`EXCEPTION_AUTHORITY_BLOCKED` and the ordinary risk stays active. There is no
permissive default: absent valid materiality configuration fails closed.

### 2.11 One calculator, two schedules

Targeted recalculation and the hourly full reconciliation call the *same* pure
calculator over the same evidence-snapshot type. That is what makes
`targeted result == full-sweep result` for identical as-of authority a testable
property rather than a hope, and it is why a deliberately dropped trigger is
repaired by the next successful sweep.

### 2.12 No write path exists

There is no stock Preview, Approval, Command, Outbox, Adapter write or Readback
in this slice, and no `STOCK_CHANGE`. The absence is enforced by architecture
tests over the new module, not merely by the absence of code today.

## 3. Module shape

A new Spring Modulith module `availabilityrisk` owns the authorities the
Contract's §7 matrix assigns to nobody today: supply ownership and
deduplication, inbound eligibility attestation, lead-time/safety policy, demand
policy, and deterministic risk and priority. It is registered in
`ModulithArchitectureTest`'s exact module list in alphabetical position.

It creates no parallel authority. It reads through published contracts only:

| It needs | It asks |
| --- | --- |
| Organization/Store/Warehouse topology | `organizationaccount.OrganizationDirectory` |
| Internal Variant identity and listing mapping | `productlisting.ListingIdentityDirectory` |
| Stock, sales, return, sellability facts | `operatingfacts.OperatingFactQuery` |
| Source evidence drill-through | `operatingfacts.EvidenceQuery` |
| Profit lane | `analyticsdecision.MetricQuery` |
| Role and scope decisions | `identityaccess.BusinessAuthorization` |
| Task and exception persistence | `operationsworkflow` published contract |
| Audit | `adminobservability.audit.MetadataAuditRecorder` |

Package layout follows the established convention exactly:

```text
availabilityrisk/                      public API records, enums, query interfaces
availabilityrisk/internal/application/ services, calculator, workers
availabilityrisk/internal/domain/      pure values, no JDBC, no Spring
availabilityrisk/internal/infrastructure/jdbc/  @Repository on JdbcClient
availabilityrisk/internal/web/         package-private @RestController @ConsoleApi
availabilityrisk/internal/config/      @ConfigurationProperties
```

Time is read from an injected `java.time.Clock`. No field injection, no
`System.out`, no vendor SDK type, no `AcquisitionPort` derivative.

## 4. Shared-Spine extensions

Four existing modules are extended through their own code, so no second writer
appears:

- `operatingfacts` gains published reads its own tables already support but
  nothing exposed: per-warehouse internal stock (needed for deduplication),
  listing sellability/health (needed for censoring), and a subject list that
  includes stock observations so a variant that stocked out and stopped selling
  cannot vanish from the run.
- `identityaccess` gains the Contract's named business roles and the new action
  scopes, with forward-only seeding of `iam.action_scope` and
  `iam.business_role_action_scope`, plus new `OwnedResource.Kind` values. The
  Owner sits with the Risk Authority they designate rather than below it:
  granting a role an approval action it could never exercise would be a
  contradiction in the reviewed matrix, and separation is enforced on identity
  rather than on role, so it still binds them.
- `operationsworkflow` gains the cause-keyed case identity, the two-stage
  action/verification lifecycle, reopen/escalation and the accepted-exception
  authority.
- `adminobservability` gains the `AVAILABILITY_RISK` audit source domain, with
  the matching forward-only `CHECK` constraint widening.

### 2.13 A run of evaluations is stored, because it cannot be recovered

The activation policy distinguishes a HIGH that has held across several
evaluations from one that appeared in the last cycle. That distinction is not
recoverable from the projection: a rebuild writes the current answer, and the
generations behind it record windows and factors, not lane runs. So the child
carries the run it is on, the writer extends or restarts it, and a shape
constraint refuses a count without the lane it counts.

The alternative — activating on first sighting — fills the queue with work that
resolves itself before anybody opens it. An operator who learns within a week
that the queue cries wolf skims the CRITICAL rows along with the rest, and the
whole product's value rests on those rows being trusted.

A defect is exempt from that gate. A blocker is a blocker on its first sighting,
and waiting for it to repeat would leave the calculation blind for exactly as
long as the gate lasts.

### 2.14 The response clock starts at the fact, not at the worker

Latency is measured from the instant a fact was accepted rather than from the
instant a worker noticed it. Measuring from the scan would make a backlog
invisible, and a backlog is the one thing the clock exists to expose.

Source latency and internal latency are separate columns because they are
separate incidents with separate owners. A marketplace that publishes an hour
late and a worker running an hour behind look identical in one combined number
and need entirely different responses.

Two bounds, not one. The hard bound is what no single recalculation may exceed
and is evaluated per observation; the distribution target is what the product
promises for the lane that matters most and can only be evaluated over a
window. Reporting only the percentile would hide one recalculation that took an
hour; reporting only the worst case would call a healthy system unhealthy after
one slow afternoon.

### 2.16 A case is closed by the calculation, not by a click

The second stage asks whether the risk improved, and only a fresh reading of
the same evidence can answer it. So the recalculation that raised a case is
also what verifies it: it reports one fact — whether the cause-specific
condition holds right now — and the workflow authority turns that into an
outcome against the governed window.

The division matters. A caller that could name the outcome directly could name
success on the first good reading, and "improved" is not "improved and stayed
improved". Keeping the window rule in the authority that owns the case means
there is one place where success is decided.

Cases are found by the child they were raised against rather than by their
cause, because by the time a cause is repaired the recalculated child no longer
carries it. A cause-keyed lookup would find nothing at exactly the moment the
good news arrived.

What counts as repaired depends on what was wrong. A shortage is repaired when
the lane falls back below the activation band; a defect is repaired when the
defect is gone and the evidence is usable again. Judging a repaired data source
by the lane would refuse to close it while a real, correctly calculated shortage
remained — which is a different case with a different owner.

### 2.15 A pulled feed needs a position that survives a restart

Consuming a fact never makes the fact authority depend on its consumers, so the
change feed is pulled. A pulled feed needs a stored position: without one a
worker either re-reads every fact ever accepted or starts silently from now and
drops whatever arrived while it was down. V0032 adds it.

Re-reading the feed boundary is a no-op rather than a loop, because a fact is
enqueued only when its variant has not already been recalculated for one at
least as recent. A hundred observations in a minute are one recalculation, and
the earliest accepted instant wins so a later fact cannot restart a clock that
is already running.

### 2.17 Frozen Deep Review findings close through one forward authority

V0034 closes the one-shot Frozen Finding Set without changing V0001–V0033. The
accepted-fact cursor is now the total tuple `(ingestion_time, provenance_id,
item_key)` and starts with deterministic backfill, so equal timestamps, restart
and page boundaries cannot drop facts. Full reconciliation is keyset-paged to
exhaustion, isolates a failing variant, records durable page progress and repairs
only variants that actually succeeded.

The same migration and service layer add the controls that the first
implementation could not infer safely: Product as an independent IAM scope;
live reserved/QC/damaged/written-off/sellable stock state; an append-only return
transport/QC/re-entry ledger; governed priority and return-quality policy;
relational tenant binding for cases, children, exceptions and human actors; and
automatic accepted-exception revalidation when evidence, cause, scope,
materiality policy or approving authority changes. The console has no endpoint
that can declare outcome success: only the recalculation-owned observation path
can do that.

## 5. Schema, V0030 onward

Applied migrations V0001–V0029 are untouched. New work is forward-only. Every
new table is registered in `platform.control_route_inventory` and added to the
`FlywayMigrationIT` table inventory, and the application role receives the
narrowest grant that the table's semantics allow — append-only evidence gets
`SELECT, INSERT`; state-carrying rows get `SELECT, INSERT, UPDATE`; nothing gets
`DELETE`.

## 6. What exists

The complete mandatory product path is implemented and exercised end to end.
Concretely:

- `availabilityrisk` registered as the eleventh Spring Modulith module, passing
  every boundary, authority and rule-sensitivity check;
- V0030: nineteen tables carrying the policies, the attestation, the projection,
  the queue, the reconciliation run, the SLO observation, the cause-keyed case
  and the accepted exception, with the fail-closed rules as constraints;
- the Contract's accountable roles and six action scopes, seeded and pinned;
- the deterministic demand policy, the two risk calculators, the policy-derived
  lane thresholds and the lexicographic rank;
- the published fact reads the calculation needs — sellability, per-warehouse
  internal stock, the merged availability timeline, per-day completed units and
  the accepted-fact change feed;
- policy resolution by exact scoped fallback, the evidence gatherer, the
  calculation service and the projection writer;
- the end-to-end loop from seeded facts to a written card with two children,
  including the equality of the targeted and sweep paths;
- the accountable-case authority: a published intake port taking plain
  identifiers, cause-idempotent activation whose duplicate is caught by the
  database rather than by a check two threads can both pass, and a state
  machine with no path from a recorded action to success and only calculated
  regression or exception invalidation able to reopen the same closed case;
- the read surface: a scope-narrowing console API and the Stockout &
  Availability queue screen, where a child's evidence tone is a separate
  attribute from its lane so a provisional critical cannot be styled as a
  confirmed one;
- V0031: the run of evaluations each child is on, which is what makes the
  sustained-HIGH rule expressible at all;
- the activation policy and the service that applies it — CRITICAL on the cycle
  that found it, a blocker on its first sighting under its own clock, HIGH only
  once sustained, WATCH never — with the cause key built from the subject's
  business key so a projection rebuild cannot raise a second case for work
  somebody already holds;
- accepted-exception governance: proportional authority sized by a published
  materiality version, requester separation enforced in the service and again in
  the database, a bounded and reviewable grant, and expiry or invalidation that
  returns the same case to somebody with its journal intact;
- V0033 and automatic outcome verification: the calculation reports whether the
  cause a case was raised for is repaired, the workflow authority turns that
  into continued verification, a verified success once the improvement has held
  through the governed window, a same-case reopen when it comes back, or a
  failure once the outcome was actually due — and the window's start is stored
  because it is not recoverable from anything else;
- V0032 and the recalculation loop: the pulled accepted-fact feed with a durable
  position, the targeted worker with its response evidence, and the hourly sweep
  that repairs what targeting missed, expires lapsed acceptances and counts
  inbound that has stopped being supply;
- V0034 and the Frozen Finding Set closure: total accepted-fact cursor and
  startup backfill; unbounded keyset-paged reconciliation with per-variant
  isolation, durable progress and abandoned-run recovery; Product-scoped reads and mutations; live warehouse QC/damage/
  write-off/sellability; returned-goods quality guardrail and attributable
  ledger re-entry; governed inbound and policy lifecycle APIs; policy-derived
  priority digest; and automatic accepted-exception revalidation;
- the loop's own health as named operator incidents rather than quiet counters;
- the accountable-work surface: the case queue, journal, action, verification,
  escalation and exception routes, each behind the grant it actually needs, and
  the console panel that shows the two clocks apart and offers no control that
  means "seen".

The [acceptance status](../../07-phase-evidence/SLICE-V1-002/acceptance-status.md)
records exactly which criteria remain partial and why. The declared 5,000-
variant profile now has real-PostgreSQL keyset traversal evidence and a complete
five-page worker capacity test, while automatic governance-drift detection,
restart recovery and the return-quality/re-entry product path are executable.
Independent Controller Final Closure remains the only authority for
`S2-AC-100`.

## 7. What this design does not do

It implements no replenishment quantity or date, no purchase or supplier
execution, no receiving or WMS workflow, no Dead Stock / Ageing / Slow-moving /
Overstock loop, no allocation or transfer, no clearance, no advertising
intervention, no target stock, no stock write path of any kind, no predictive
demand or lost-demand model, no Owner demand plan, no external AI call and no
real provider call. These remain durable future V1 obligations.

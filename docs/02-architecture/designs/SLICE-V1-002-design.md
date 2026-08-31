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

Lead-time/safety, demand-window selection, work activation, priority and
exception materiality are all versioned policies resolved by exact scoped
fallback. Missing, expired, overlapping or conflicting policy never degrades to
a zero or to an implementation default; it produces `POLICY_BLOCKED`. Effective
periods are enforced by PostgreSQL `EXCLUDE USING gist` no-overlap constraints
on the active rows, the same mechanism `core.cost_version` already uses, so two
overlapping active versions cannot exist even under concurrency.

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
  `iam.business_role_action_scope`, plus new `OwnedResource.Kind` values.
- `operationsworkflow` gains the cause-keyed case identity, the two-stage
  action/verification lifecycle, reopen/escalation and the accepted-exception
  authority.
- `adminobservability` gains the `AVAILABILITY_RISK` audit source domain, with
  the matching forward-only `CHECK` constraint widening.

## 5. Schema, V0030 onward

Applied migrations V0001–V0029 are untouched. New work is forward-only. Every
new table is registered in `platform.control_route_inventory` and added to the
`FlywayMigrationIT` table inventory, and the application role receives the
narrowest grant that the table's semantics allow — append-only evidence gets
`SELECT, INSERT`; state-carrying rows get `SELECT, INSERT, UPDATE`; nothing gets
`DELETE`.

## 6. What exists at this checkpoint

The calculation core is complete and proven; the operating surface around it is
not yet built. Concretely, the following are implemented and tested:

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
  including the equality of the targeted and sweep paths.

The following are designed above but not yet implemented: case activation and
the two-stage action and verification lifecycle, accepted-exception governance
and approval escalation, the targeted and hourly workers with their SLO
evidence, and the console API, queue UI and browser journey. The
[acceptance status](../../07-phase-evidence/SLICE-V1-002/acceptance-status.md)
records exactly which criteria that leaves unproven.

## 7. What this design does not do

It implements no replenishment quantity or date, no purchase or supplier
execution, no receiving or WMS workflow, no Dead Stock / Ageing / Slow-moving /
Overstock loop, no allocation or transfer, no clearance, no advertising
intervention, no target stock, no stock write path of any kind, no predictive
demand or lost-demand model, no Owner demand plan, no external AI call and no
real provider call. These remain durable future V1 obligations.

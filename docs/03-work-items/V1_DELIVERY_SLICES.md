# MarketOps V1 Production Delivery Slices

```yaml
document_type: active_delivery_plan
product_version: V1
delivery_model: PRODUCTION_VERTICAL_SLICES
source_contract: docs/01-requirements/V1_PRODUCT_CONTRACT.md
active_slice: SLICE-V1-003
old_phase_zero_backlog: SUPERSEDED_AS_ACTIVE_EXECUTION_PLAN
effective_condition: PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION
```

The roadmap state below is durable but conditional: it becomes active only when
this exact reviewed baseline is present on protected `main` after independent
Controller review and Human Owner merge authorization.

## 1. Purpose

A Production Delivery Slice is the primary V1 delivery and business-communication
unit. Each Slice must end in an observable, production-grade operating capability,
not merely a completed technical layer. Work Packages, implementation tranches and
Pull Requests remain useful engineering and Git units, but do not independently
redefine product scope or introduce mandatory Design approval.

```text
V1 Product Contract
└── Production Delivery Slice
    ├── Implementation Tranche / Work Package
    ├── Pull Request(s)
    └── Slice Evidence and Release Gate
```

A Slice may enter bounded production after its own Final and Production Enablement
Gates. `V1_PRODUCT_COMPLETE` is a separate integration Gate after all required
Slices are assembled.

## 2. Shared-Spine rule

Every Slice may extend the common Shared Spine only to the production depth needed
by that Slice. It must reuse the existing authority, data and security paths and
must not create a second scheduler, Raw path, Metric truth, AI fact source, Policy
authority, Command writer, audit trail or Marketplace adapter family.

See `docs/02-architecture/V1_SHARED_SPINE.md`.

## 3. Delivery sequence

| Order | Slice | User outcome | New controlled-write target | Status |
| ---: | --- | --- | --- | --- |
| 1 | `SLICE-V1-001 — SKU Growth & Profit Diagnostic Loop` | Cross-domain SKU diagnosis, evidence-linked AI recommendation, task/approval and dual-platform guarded price execution | `PRICE_CHANGE` on Ozon and WB | CONTRACT_APPROVED_EFFECTIVE_ON_PROTECTED_MAIN |
| 2 | `SLICE-V1-002 — Stockout & Availability Risk with Accountable Response` | Trusted channel and company availability risk, grouped queue, deterministic priority, cause-routed accountable Case with two-stage action and outcome verification, governed Accepted Exception and targeted plus hourly recalculation | none; this Slice has no controlled-write target | ENGINEERING_MERGED_FORMAL_CLOSURE_ACCEPTED_RELEASE_DEFERRED |
| 3 | `SLICE-V1-003 — Advertising & Traffic Efficiency` | Campaign/target efficiency tied to inventory, deterministic ad-linked conversion and dual-axis Advertising Contribution Profit, with deterministic lanes, non-compensating priority, accountable Task, governed Manual Shadow on both platforms, and one bounded per-command approved bid change | `AD_BID_CHANGE` on Ozon and WB | CONTRACT_ACCEPTED_FULL_SCOPE_IMPLEMENTATION |
| 4 | `SLICE-V1-004 — Promotion & Listing Conversion` | Listing Health, content/promotion diagnosis, experiments and governed content/promotion workflow | selected promotion/listing command | PLANNED |
| 5 | `SLICE-V1-005 — Order, Fulfillment & Return Control` | Ozon/WB FBO/FBS order state, SLA, return/QC and exception control | selected low-risk order action | PLANNED |
| 6 | `SLICE-V1-006 — Finance & Contribution Profit Reconciliation` | Operational/Settled Contribution Profit, late adjustments, reconciliation and close workflow | none required by default | PLANNED |
| 7 | `SLICE-V1-007 — Cross-domain Command Center & V1 Gate` | Integrated daily command center, policy management, cross-domain prioritization and V1 readiness | expand only capabilities that independently pass | PLANNED |

### SLICE-V1-003 controlled-write narrowing, with its original provenance

The row above records the accepted Contract, which names one exact controlled
write where this roadmap originally left the choice open. The original text is
preserved here so the change is visible rather than silent:

```text
SLICE-V1-003 — Advertising & Traffic Efficiency
Campaign/target efficiency tied to inventory, conversion and Contribution Profit
new controlled-write target: selected budget/bid/campaign command
```

The accepted Contract selects `AD_BID_CHANGE` and only `AD_BID_CHANGE`. Budget
change, campaign pause/resume, bidding-strategy or mode switch, campaign, ad
group, target or keyword creation, deletion or restructuring, negative keywords,
search terms, creative and content writes, portfolio reallocation and standing
policy automation are explicitly out of scope. They remain future product
Capabilities for a later Slice or an accepted additive Amendment; they are not
deferred evidence and they are not implied by this one. Both platforms still
receive the complete governed Manual Shadow path, so a human may lawfully perform
those actions in the official console under an exact Manual Execution Packet — a
Packet never creates a hidden Provider API path.

The Slice's Provider write paths remain structurally unreachable. Engineering
closure for it is `CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS`, and
`S3-REL-001` through `S3-REL-024` stay production-blocking.

### SLICE-V1-002 scope narrowing, with its original provenance

The row above records the accepted Contract, which is narrower than the row this
roadmap originally carried. The original text is preserved here so the change is
visible rather than silent:

```text
SLICE-V1-002 — Inventory & Availability Optimization
Internal/platform stock truth, FBO/FBS drift, stockout/overstock diagnosis and
allocation workflow
new controlled-write target: selected STOCK_CHANGE
```

The accepted Contract removes Overstock, Ageing, Dead Stock and Slow-moving
diagnosis, Allocation and Transfer, replenishment quantity and order date, and
the `STOCK_CHANGE` capability itself. Those remain future product Capabilities
for a later Slice; they are not deferred evidence and they are not implied by
this one. What the Slice keeps is the availability question a person can act on
and be held to: what is running out, who owns it, what they did, and whether it
actually improved.

The order is the default dependency path, not an artificial ban on safe overlap.
A later Slice may start discovery or fixture work when it does not compete with or
silently change the active Slice Contract. Production implementation remains
anchored to the active Slice unless the Controller explicitly authorizes bounded
parallel work.

## 4. Slice lifecycle

```text
DRAFT_SLICE
→ CONTRACT_IN_REVIEW
→ CONTRACT_APPROVED
→ IMPLEMENTING
→ DEEP_REVIEW
→ REWORK_VERIFY
→ FINAL_GATE
→ RELEASE_READY
→ CONTROLLED_PRODUCTION
→ EXPANDED_PRODUCTION
→ SLICE_COMPLETE
```

Blocking classifications:

```text
BLOCKED_OWNER_AUTHORITY
BLOCKED_EXTERNAL_EVIDENCE
BLOCKED_CREDENTIAL_OR_ACCESS
BLOCKED_DATA_SAMPLE
BLOCKED_LEGAL_OR_SECURITY_RELEASE
```

A blocker must identify the exact acceptance item affected. It must not freeze
unrelated implementation that can proceed safely.

## 5. Review model

Each Slice normally has three Controller decision points:

1. `CONTRACT_GATE` — objective, boundaries, hard invariants, acceptance and Owner
   decisions are complete enough to authorize Detailed Design + Initial Full
   Implementation;
2. `DEEP_REVIEW` — source-first combined design/implementation review against the
   actual diff, tests and runtime evidence;
3. `FINAL_GATE` — review of the reworked exact Head and required evidence before
   merge/release readiness.

A separate pre-implementation Design Gate is triggered only by the conditions in
ADR-0006 and the active Slice Contract.

## 6. Production-release model

```text
Full production-quality implementation
→ Capability disabled by default
→ controlled Pilot Cohort
→ evidence-based expansion
→ eligible-scope general availability
```

Pilot scope is a release control, not a temporary implementation. No Slice may
hard-code its data model or algorithm for a handful of pilot SKUs.

## 7. Cross-Slice V1 invariants

- official Marketplace APIs/reports are the only programmatic platform source;
- exact Raw evidence is immutable and replayable;
- Core/Ledger/Mart facts remain deterministic and versioned;
- unknown source state is first-class and fail-closed;
- Buyer PII and Secret material remain outside AI/general Mart;
- AI cannot become the Metric, Policy, Approval, Command or Credential authority;
- every write uses Recommendation/Evidence, deterministic Gates, idempotent
  Command, Readback, Audit, Kill Switch and outcome follow-up;
- Ozon and WB platform semantics remain independent behind one internal business
  Capability model;
- historical migrations and evidence are never rewritten to fit a new Slice;
- production enablement is separate from merge and remains Human Owner authority.
- bounded real-write evidence requires Gate EV before the first real write;
  ongoing controlled Pilot enablement separately requires Gate E.

## 8. Slice documentation requirement

Every active Slice has one canonical contract under `docs/03-work-items/`. Its
implementation tranches may use Issues/PRs, but those records must cite the Slice
and may refine only `HOW`, not redefine `WHAT`, hard boundaries or acceptance.

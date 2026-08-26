# DR-0004 — Engineering Execution & Closure Protocol Alignment

```yaml
document_type: decision_request
decision_id: DR-0004
repository: Corwin-Code/marketops-platform
required_base: dce9eecb9500504c15e63b8939a39822f87f883d
required_base_tree: 37feff5306f8c3c63022243bbcdbc6e7d29fd412
change_class: GOVERNANCE_ONLY
status: PROPOSED_PENDING_EXACT_OWNER_ACCEPTANCE
product_scope_change: NONE
slice_scope_change: NONE
active_slice_contract_change: PROHIBITED
active_slice_contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
production_enablement: NOT_AUTHORIZED
deployment: NOT_AUTHORIZED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
```

## Problem

DR-0003 correctly established Production Vertical Slices and Contract-governed Vibe
Coding, but the current execution protocol still permits ambiguities that can
recreate unnecessary synchronous Gates: Maker remote Git publication is not
separated from local implementation; accepted Contracts are hash-bound but not
explicitly byte-frozen with additive Amendments; Deep Review does not explicitly
freeze a single Finding Set; Final Gate can still be interpreted as a second
discovery review; Slice Owner Formal Closure / Closure Snapshot are not explicit;
and source precedence does not yet encode a dual Normative-Truth /
Implementation-Fact conflict model.

## Decisions

### D4-01 — Contract acceptance is implementation authorization
Human Owner acceptance of exact Contract path + bytes + SHA-256 grants the full
Execution Envelope carried by that Contract and the project-level policy. No
routine Design/Implementation/Test re-authorization is required.

### D4-02 — Original accepted Contracts are immutable
An accepted original Contract is permanently byte-frozen. Normative changes are
represented as `Original Contract + Accepted Amendment(s)`, never silent in-place
editing and re-hashing of the same Contract.

### D4-03 — Detailed Design evolves inside implementation
Claude continuously evolves Detailed Design, Execution Plan, source, tests,
migrations, browser evidence, canonical docs and local checkpoints while the
accepted Contract remains satisfiable. Technical difficulty alone never triggers
a pause.

### D4-04 — Three-level Execution Envelope
Level 1: ordinary local reversible engineering work, isolated/ephemeral tests and
local Git checkpoints.
Level 2: exact Contract-pre-authorized named non-production shared/provider
side-effects with environment/data/side-effect/prohibition boundaries.
Level 3: remote Git writes, PR/merge, production DB/migrations/Credentials/
deployment, destructive or irreversible operations, and real production business
side-effects. Level 3 always requires a dedicated authority Contract/Gate.

### D4-05 — Claude implementation and remote publication are separate
Claude's ordinary Slice authority ends at an exact local Git commit/tree plus
implementation/evidence handoff. Ordinary Claude authority does not include
`git push`, remote branch/tag write, PR creation/update, Ready or merge.
The named remote publication delegate, normally Codex, verifies and publishes the
exact checkpoint/tree without redesigning or reconstructing it.

### D4-06 — Deep Review is one-shot discovery/falsification
Formal Controller Deep Review inspects the complete transitive Slice surface and
produces one hash-bound Frozen Finding Set. A later miss based on evidence that
already existed and should reasonably have been reviewed is classified
`CONTROLLER_REVIEW_COVERAGE_FAILURE`, not used to justify endless new discovery
rounds.

### D4-07 — Codex receives one complete rework contract
Codex receives `Original Contract + Accepted Amendments + Frozen Finding Set` and
performs one continuous Root-Cause Rework/Fix/Verify cycle, including same-class
scan, transitive impact, test hardening, regression, runtime evidence and
canonical-doc synchronization.

### D4-08 — Final Gate is closure verification
Final Gate verifies finding root-cause closure, Amendment implementation, no test
weakening, transitive coverage, regression/evidence and final Contract
satisfaction. It is not open-ended discovery. Reopening requires materially new,
previously unavailable severe evidence.

### D4-09 — Owner Formal Closure is not a third engineering review
Owner verifies exact Contract/Amendments, final source/Git/migration identity,
Controller Closure PASS, Owner-only conditional acceptance and absence of a new
Owner-only blocking business fact. Owner does not re-review technical mechanics.

### D4-10 — Closure Snapshot is mandatory
Every closed Delivery Slice records Contract/Amendment hashes, final source/tree/
migration identity, Finding Set identity, external evidence/capability state,
Acceptance results, release state, non-blocking debt/enhancements and next-Slice
prerequisites. The next Slice starts from the latest Closure Snapshot rather than
rebuilding all history.

### D4-11 — Dual truth model
Normative truth: `Owner Decision → Contract/Amendment → ADR/canonical normative docs`.
Implementation fact: `runtime/DB/external evidence → migration/schema → exact source/Git → tests/snapshots`.
Conflicts are classified `IMPLEMENTATION_DEFECT`, `CONTRACT_DEFECT` or
`DOCUMENTATION_DRIFT`; no layer silently overwrites the other.

## Non-goals

DR-0004 does not change V1 product scope, SLICE-V1-001 bytes/SHA, provider
decisions, production authority, Gate EV/E, V0001–V0010 or runtime product source.

## Required repository effect

Canonical governance must implement:
1. project-level Execution Envelope Policy;
2. immutable original Contract + additive Amendment rule;
3. Claude local checkpoint authority and separate remote publication;
4. one-shot Deep Review + Frozen Finding Set SHA-256;
5. Controller Review Coverage Failure;
6. Final Gate closure-verification semantics;
7. Owner Formal Closure + mandatory Closure Snapshot;
8. dual truth model and conflict classification;
9. mutation-sensitive validators/tests;
10. no change to SLICE-V1-001 Contract.

## Acceptance criteria

- D4-AC-001 accepted Contract cannot be edited in place; Amendment identity required.
- D4-AC-002 Claude ordinary instructions contain no remote push/PR authority.
- D4-AC-003 Level 1/2/3 Execution Envelope is canonical and fail-closed.
- D4-AC-004 remote publication preserves exact checkpoint/tree.
- D4-AC-005 Deep Review creates one hash-bound Frozen Finding Set.
- D4-AC-006 Final Gate rejects open-ended second discovery.
- D4-AC-007 Owner Formal Closure and Closure Snapshot are explicit.
- D4-AC-008 dual truth and defect classification are canonical.
- D4-AC-009 validators reject regression of D4-AC-001..008.
- D4-AC-010 SLICE-V1-001 SHA remains exactly `0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5`.
- D4-AC-011 backend/frontend/infra/fixtures and V0001–V0010 remain unchanged.
- D4-AC-012 governance/readiness/tests/CI pass on exact PR Head.

## Effective condition

DR-0004 becomes active only after exact Human Owner acceptance of this DR plus the
exact accompanying Execution Envelope and Closure Snapshot standards, Codex
governance-only implementation, independent Controller review, Human Owner merge
authorization and protected merge. Until then DR-0003 remains authoritative.

## Lifecycle after effect

```text
Claude local Full Implementation
→ exact local checkpoint
→ Codex remote publication to Draft PR
→ one Controller Deep Review / Frozen Finding Set
→ one Codex Root-Cause Rework/Fix/Verify
→ Controller Final closure verification
→ protected merge / Gate EV / Gate E as applicable
→ Controller Slice Closure
→ Owner Formal Closure
→ Closure Snapshot
→ next Slice
```

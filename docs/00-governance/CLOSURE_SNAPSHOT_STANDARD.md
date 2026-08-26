# Closure Snapshot Standard

```yaml
standard_id: CLOSURE_SNAPSHOT_V1
status: PROPOSED_BY_DR_0004
owner_formal_closure_required: true
engineering_review_gate: false
```

A Closure Snapshot is the durable cross-window/cross-agent handoff for a formally
closed Production Delivery Slice.

## Required identity
- Product Version / Slice ID / Closure Snapshot ID / date;
- Original Contract path + SHA-256;
- accepted Amendment paths + SHA-256;
- Controller Deep Review and Frozen Finding Set SHA-256;
- Codex final rework Head/tree;
- Controller Final Gate identity;
- merge/squash commit/tree/parents;
- deployed/released identity where applicable;
- Owner Formal Closure identity.

## Normative truth
Record active Owner Decisions, Contract/Amendments, ADRs, authority boundaries,
non-goals and supersessions.

## Implementation fact
Record final source/tree, migration inventory, schema/runtime/provider evidence,
tests/CI/DR evidence and actual Capability/enablement state.

## Acceptance
Every current-Slice criterion is explicitly `VERIFIED`,
`OWNER_ACCEPTED_CONDITIONAL` or `NOT_APPLICABLE`. Unmet current-Slice Acceptance
cannot be hidden as debt.

## External evidence
Use explicit classes such as `UNVERIFIED`, `VERIFIED_PUBLIC_SOURCE`,
`VERIFIED_REAL_ACCOUNT`, `VERIFIED_REAL_PROVIDER`,
`VERIFIED_CONTROLLED_PRODUCTION`.

## Residual items
Separate `NON_BLOCKING_DEBT`, `PRODUCT_ENHANCEMENT`, `NEXT_SLICE_REQUIREMENT` and
`EXTERNAL_MONITORING`.

## Owner Formal Closure
Owner confirms exact Contract/Amendment set, final source/Git/migration identity,
Controller Closure PASS, Owner-only conditions and absence of a new Owner-only
blocking fact. It is not a technical review.

## Publication and next Slice
The exact Owner-accepted Snapshot is published through protected remote
publication without reopening engineering discovery. The next Slice starts from
latest Closure Snapshot + current Product Contract/Owner Decisions + exact
protected-main identity.

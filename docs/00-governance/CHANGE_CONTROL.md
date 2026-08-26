# Change Control v2

## Decision Request required

Create a Decision Request and an additive Contract Amendment when applicable
before changing:

- explicit Human Owner product intent, commercial risk or V1 scope;
- an accepted Decision/ADR or active Slice Contract;
- financial, inventory, order, return or Metric semantics;
- source of truth, sole writer/executor or module authority;
- external provider, identity boundary or deployment topology;
- Secret, PII, retention, cross-border or legal boundary;
- destructive/irreversible migration or historical data rewrite;
- real Marketplace write Capability/automation risk class;
- security, audit, test, recovery or production Gate strength.

## Decision Request not required

Within an approved Slice Contract, normal engineering choices do not require an
Owner question or ADR, including:

- class/package decomposition inside an accepted module boundary;
- SQL/index/query implementation;
- Spring wiring, internal DTO and exception hierarchy;
- library usage that does not add a new external service/authority;
- test organization, refactoring and naming;
- bounded performance tuning and implementation details.

These choices remain reviewable against the Contract.

## Conditional Design Gate

A material question may pause implementation only when it meets a trigger in
`AI_OPERATING_MODEL.md`. Ask one conclusion-changing question, not a questionnaire.
Record the answer through an additive accepted Contract Amendment and/or Decision
before implementation continues; never edit an accepted original Contract.

## Required Decision Request content

```text
ID and authority
Problem and evidence
Current decision/contract
Proposed decision and alternatives
Supersession/compatibility matrix
Affected requirements, modules and data
Migration/backfill/rollback
Security/privacy/legal/AI impact
Testing and evidence
Cost/operations
Owner decision or Controller recommendation
Effective condition and status
```

## Supersession rule

Never delete history to make a new rule look original. Mark the prior decision or
plan `SUPERSEDED`, retain its evidence and state exactly which clauses remain
binding.

## Accepted Contract immutability

An accepted original Product or Slice Contract is permanently byte-frozen.
Normative change is represented only as `Original Contract + Accepted
Amendment(s)`, with every Amendment carrying its own path, bytes, SHA-256,
acceptance identity, affected clauses and effective condition. Editing the
original in place and coordinating a new hash is prohibited.

Controller interpretation may be non-expansive clarification only. Repeated
clarifications may not accumulate into hidden scope, authority, risk or
Acceptance expansion. Any such change requires the Amendment path above.

If normative text and implementation evidence conflict, preserve both and
classify the issue as `IMPLEMENTATION_DEFECT`, `CONTRACT_DEFECT` or
`DOCUMENTATION_DRIFT`; no layer silently overwrites another.

## No silent compromise

No placeholder, fixture-only substitute, missing provider access, skipped test,
unknown-state coercion or deferred control may be represented as production-grade.
A fail-closed external evidence state is acceptable only when its consuming Gate
is explicit.

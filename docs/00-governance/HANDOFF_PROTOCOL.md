# Handoff Protocol v2

## 1. Controller → Claude

A valid implementation handoff contains:

- Product Version and Delivery Slice ID;
- exact active Contract path, approved SHA-256 and ADR paths;
- business outcome, scope and non-goals;
- Owner decisions and external evidence states;
- authority/source-of-truth and hard invariants;
- data, AI, security, failure/recovery and migration obligations;
- acceptance criteria and evidence classes;
- Conditional Design Gate triggers and implementation freedom;
- exact authorization `FULL_SCOPE_IMPLEMENTATION`.

## 2. Claude Detailed Design + Initial Full Implementation

Claude first maps the implementation, then continues directly into source,
migration, tests, frontend, IaC, documentation and runbooks in one Draft PR. A
separate Design return is not required unless a material Conditional Design Gate
trigger appears.

Claude returns exact commands/results, evidence classification, unresolved
external facts and a request for `CONTROLLER_DEEP_REVIEW`.

## 3. GPT Deep Review

GPT inspects the actual repository and produces a complete severity-labeled
finding ledger. The normal handoff to Codex is `READY_FOR_CODEX_REWORK`; no
finding may silently change the accepted Product Contract.

## 4. Codex Full Rework / Fix / Verify

Codex may modify every in-scope surface required to resolve the findings and
produce a production-grade result. It keeps the same Slice Contract and Draft PR,
runs exact evidence and requests the Final PR Gate.

## 5. GPT Final Gate

GPT reviews the current PR Head, tree, diff, migrations, tests, evidence and CI.
Only `APPROVE_FOR_HUMAN_MERGE` permits the Human Owner or active D-17 delegate to
execute the protected merge.

## 6. Bounded verification and Capability enablement

Implementation and merge keep all real-write Capabilities disabled. Before the
first real write used for evidence, Gate EV must issue
`AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION` for the exact Human Owner-approved
Platform, opaque Account/Store, Capability, SKU allowlist, time/exposure envelope,
operator/abort owner, Guardrails/Dry Run, Kill Switch, pre-state,
Readback/Restore/Compensate, unknown-result and Audit plan.

Gate EV permits only supervised bounded evidence generation; it does not permit
recurring execution, a general Pilot or production release. Each platform
Capability then requires its own Gate E, consuming valid Gate-EV evidence, and a
separate Human Owner production authorization before ongoing Pilot enablement.

## 7. Handoff packet fields

Every major handoff identifies:

```text
SLICE / CONTRACT / BASE / HEAD / TREE / PR
DECISIONS / EXTERNAL EVIDENCE
CHANGED FILES / MIGRATIONS
COMMANDS / RESULTS / CI
SECURITY / PRIVACY / AI / WRITE IMPACT
GATE_EV AUTHORIZATION / EXACT SCOPE / EXPIRY / EVIDENCE
FINDINGS / RISKS / REQUESTED VERDICT
NEXT_AUTHORIZED_ACTOR / NEXT_ACTION
```

## 8. Git guidance

Follow `OWNER_GIT_WORKFLOW_GUIDE.md` while Current State says `REQUIRED`. D-17
changes only who executes an already authorized Ready/merge action; it does not
transfer Controller, business, credential or production authority.

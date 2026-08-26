# Handoff Protocol v2

## 1. Controller → Claude

A valid implementation handoff contains:

- Product Version and Delivery Slice ID;
- exact immutable original Contract path, approved SHA-256, accepted additive
  Amendment paths/SHA-256 and ADR paths;
- business outcome, scope and non-goals;
- Owner decisions and external evidence states;
- authority/source-of-truth and hard invariants;
- data, AI, security, failure/recovery and migration obligations;
- acceptance criteria and evidence classes;
- Conditional Design Gate triggers and implementation freedom;
- exact authorization `FULL_SCOPE_IMPLEMENTATION`.

## 2. Claude Detailed Design + Initial Full Implementation

Claude first maps the implementation, then continues directly into source,
migration, tests, frontend, IaC, documentation and runbooks under local Level-1
plus only explicit Contract-pre-authorized Level-2 authority. A separate Design
return is not required unless a material Conditional Design Gate trigger appears.

Claude's ordinary authority ends at an exact local commit/tree plus implementation
and evidence handoff. It does not include push, remote branch/tag mutation, PR
create/update, Ready or merge. Claude returns exact commands/results, evidence
classification, unresolved external facts and a request for
`REMOTE_PUBLICATION`.

## 3. Codex exact remote publication

Under a dedicated Level-3 Remote Publication authority, Codex or the named Owner
delegate verifies the exact local commit/tree, original Contract and Amendment
identities, target repository/base/branch and prohibitions, then transports that
checkpoint to one Draft PR. It may not reconstruct, redesign or improve the
implementation during publication. If exact transport cannot be proven, stop and
request a hash-verifiable shared worktree, Git bundle, patch series or equivalent.

## 4. GPT one-shot Deep Review

GPT inspects the complete transitive repository/PR/evidence surface once and
produces one complete severity-labeled Frozen Finding Set with stable IDs,
reviewed Base/Head/tree, evidence inventory, artifact path and SHA-256. The normal
handoff to Codex is `READY_FOR_CODEX_REWORK`; no finding or non-expansive
interpretation may silently change the accepted Product Contract.

## 5. Codex continuous Root-Cause Rework / Fix / Verify

Codex receives `Original Contract + Accepted Amendments + Frozen Finding Set`
once. It may modify every in-scope surface required to resolve root causes,
perform same-class/transitive scans, harden tests, run regression/runtime evidence
and synchronize canonical docs. It keeps the same Draft PR, runs exact evidence
and requests the Final Gate. Findings are not drip-fed from evidence that was
already available at Deep Review.

## 6. GPT Final Gate — closure verification

GPT verifies the current PR Head/tree against the Frozen Finding Set, accepted
Amendments and complete Contract: root-cause closure, no test/control weakening,
transitive coverage and refreshed regression/evidence. It is not an open-ended
second discovery review. Reopening requires materially new, previously
unavailable severe evidence. A miss based on old available evidence is
`CONTROLLER_REVIEW_COVERAGE_FAILURE`. Only `APPROVE_FOR_HUMAN_MERGE` permits the
Human Owner or active D-17 delegate to execute the protected merge.

## 7. Bounded verification and Capability enablement

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

## 8. Slice and Owner closure

After the applicable protected merge, Gate EV, Gate E and release identities are
known, GPT performs Controller Slice Closure against the exact technical
identities. Human Owner Formal Closure then confirms the exact
Contract/Amendments, final source/Git/migration/release identity, Controller
Closure PASS, Owner-only conditions and absence of a new Owner-only blocking
fact. It is not a third engineering review.

The exact Owner-accepted Closure Snapshot required by
`CLOSURE_SNAPSHOT_STANDARD.md` is published through protected remote publication
before the next Slice starts.

## 9. Handoff packet fields

Every major handoff identifies:

```text
SLICE / ORIGINAL CONTRACT + SHA / AMENDMENTS + SHA / BASE / HEAD / TREE / PR
DECISIONS / EXTERNAL EVIDENCE
CHANGED FILES / MIGRATIONS
COMMANDS / RESULTS / CI
SECURITY / PRIVACY / AI / WRITE IMPACT
GATE_EV AUTHORIZATION / EXACT SCOPE / EXPIRY / EVIDENCE
FROZEN FINDING SET + SHA / FINDINGS / RISKS / REQUESTED VERDICT
NEXT_AUTHORIZED_ACTOR / NEXT_ACTION
```

## 10. Dual truth conflict handling

Normative Truth is Owner Decision → immutable original Contract + accepted
Amendments → ADR/canonical normative docs. Implementation Fact is
runtime/DB/external evidence → migration/schema → exact source/Git →
tests/snapshots. Handoffs preserve both and classify a conflict as
`IMPLEMENTATION_DEFECT`, `CONTRACT_DEFECT` or `DOCUMENTATION_DRIFT`; no layer
silently overwrites another.

## 11. Git guidance

Follow `OWNER_GIT_WORKFLOW_GUIDE.md` while Current State says `REQUIRED`. D-17
changes only who executes an already authorized Ready/merge action; it does not
transfer Controller, business, credential or production authority.

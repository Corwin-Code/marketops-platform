# Quality Gates v2

## G0 — Repository foundation

G0 remains historically verified. Protected PR flow, required CI, Secret scanning,
source integrity, no direct `main` push and Owner merge authority remain binding.

## Gate C — Product / Slice Contract

The Controller verifies:

- exact immutable original Slice Contract path and SHA-256 plus every accepted
  additive Amendment path and SHA-256; editing an accepted original Contract in
  place is prohibited;
- observable business/user outcome and complete in-scope loop;
- explicit non-goals and delivery boundaries;
- Owner decisions closed or assigned to exact external evidence Gates;
- source of truth, authority and hard invariants;
- data, AI, security, failure, recovery and controlled-write obligations;
- acceptance evidence and stop conditions;
- engineering freedom that does not need further approval.

Verdicts:

```text
AUTHORIZED_FOR_FULL_SCOPE_IMPLEMENTATION
TARGETED_CONTRACT_REWORK
BLOCKED_BY_OWNER_DECISION
BLOCKED_BY_EXTERNAL_CAPABILITY
```

## Conditional Design Gate

Not a default stage. Trigger only under the conditions in
`AI_OPERATING_MODEL.md`. Its result amends or confirms the Slice Contract; it must
not become an unbounded sequence of implementation-detail approvals.

## Gate D — Implementation Deep Review

After exact-checkpoint remote publication, GPT performs the single formal
discovery/falsification review of the entire transitive Slice surface against the
original Contract, accepted Amendments and Production Assurance Matrix. It
produces one complete Frozen Finding Set artifact with stable finding IDs,
reviewed Base/Head/tree, evidence inventory and SHA-256.

Verdicts:

```text
READY_FOR_CODEX_REWORK
CHANGES_REQUIRED
REJECTED_CONTRACT_VIOLATION
BLOCKED_EVIDENCE_INCOMPLETE
```

A normal outcome is `READY_FOR_CODEX_REWORK` with the Frozen Finding Set. This is
not failure; it is the independent production-hardening handoff. A later miss
based on evidence already available and reasonably reviewable here is
`CONTROLLER_REVIEW_COVERAGE_FAILURE`, not authority for endless discovery rounds.

## Gate R — Codex Rework/Fix/Verify

Codex receives `Original Contract + Accepted Amendments + Frozen Finding Set` as
one complete rework contract. It resolves all BLOCKER/MAJOR findings and
applicable MINOR findings in one continuous Root-Cause Rework/Fix/Verify cycle,
including same-class scan, transitive impact, test hardening, regression/runtime
evidence and canonical-doc synchronization. It updates the same Draft PR and may
not redefine the Contract or self-approve.

## Gate F — Final Pull Request

Gate F is closure verification against the exact Frozen Finding Set, accepted
Amendments and final Head/tree. It verifies root-cause closure, no test/control
weakening, transitive coverage, regression/evidence and final Contract
satisfaction. It is not an open-ended second discovery review. Reopening requires
materially new, previously unavailable severe evidence and an explicit recorded
reason.

Required evidence depends on the Slice but includes all applicable:

- backend build/unit/property/integration/real-PostgreSQL;
- Flyway clean/upgrade/validate and forward compatibility;
- architecture and authority tests;
- Ozon/WB contract and real controlled-provider evidence;
- replay/reconciliation/unknown-state/recovery;
- frontend lint/type/unit/browser E2E;
- auth/security/privacy/secret/dependency/SAST;
- AI structured-output/data-boundary tests;
- documentation, runbooks, traceability and migration/rollback notes;
- no unresolved BLOCKER/MAJOR.

Verdicts:

```text
APPROVE_FOR_HUMAN_MERGE
CHANGES_REQUIRED
REJECTED_CONTRACT_VIOLATION
BLOCKED_EVIDENCE_INCOMPLETE
```

Merge leaves real write Capabilities disabled unless a separate enablement Gate
has already and explicitly approved the exact deployed identity—which is normally
post-merge.

## Controller Slice Closure, Owner Formal Closure and Snapshot

After applicable merge/release identities are known, Controller Slice Closure
records technical closure of the exact Contract/Amendments and Frozen Finding
Set. Human Owner Formal Closure confirms exact source/Git/migration/release
identity, Owner-only conditions and absence of a new Owner-only blocking fact; it
is not a third engineering review.

An exact Owner-accepted Closure Snapshot conforming to
`CLOSURE_SNAPSHOT_STANDARD.md` is mandatory before the next Slice starts. Docs
remain part of Definition of Done and durable evidence, not an additional review
Gate.

## Gate EV — Bounded Real-Write Verification Authorization

Gate EV is the only authority that permits a real Marketplace write whose sole
purpose is to generate bounded `Write → Readback → Restore/Compensate` evidence
before Gate E. It is distinct from implementation, merge, deployment and ongoing
Pilot enablement.

Every Gate-EV authorization requires all of the following in one exact,
reviewable envelope:

- explicit Human Owner authorization;
- Platform, opaque Account/Store reference, Capability and SKU allowlist;
- a one-time or time-bounded verification window;
- maximum price delta and cumulative exposure;
- current official-source and real-account Capability evidence;
- current deterministic Guardrails and a passing Dry Run;
- supervised operator, abort owner and manual-stop procedure;
- global and scoped Kill Switches;
- captured pre-state;
- Readback and Restore/Compensate procedure;
- unknown-result and manual-resolution behavior;
- complete Audit and durable redacted evidence-retention plan.

Verdicts:

```text
AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION
CHANGES_REQUIRED
BLOCKED_BY_EXTERNAL_CAPABILITY
BLOCKED_EVIDENCE_INCOMPLETE
```

`AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION` authorizes only the named evidence
operation inside its scope and window. It does not authorize general Pilot
enablement, unattended recurring execution, a broad Policy, production release
or any unnamed scope. Expiry, completion, abort or scope mismatch returns the
authorization to fail-closed `NONE`.

## Gate E — Controlled Capability Enablement

For every real write Capability and platform, independently prove:

- the required real-write evidence was generated under a valid prior Gate-EV
  authorization bound to the exact Platform/Account/Store/Capability/SKU/window;
- current official API/role/quota evidence;
- exact Store/SKU/Campaign allowlist;
- deterministic Guardrail and Approval/Policy authority;
- Idempotency and unknown-result behavior;
- real `Write → Readback → Restore/Compensate` verification;
- audit, alerting and Kill Switch;
- bounded Pilot Cohort and rollback owner;
- legal/security/credential/provider readiness.

Gate E consumes Gate-EV evidence but does not retroactively authorize how it was
created. Gate E remains the only Gate that can approve ongoing controlled Pilot
production release, and its Human Owner authorization is separate from the
earlier bounded verification authorization.

Verdicts:

```text
APPROVE_FOR_CONTROLLED_PRODUCTION_RELEASE
CHANGES_REQUIRED
BLOCKED_BY_EXTERNAL_CAPABILITY
BLOCKED_EVIDENCE_INCOMPLETE
```

## Gate S — Slice Production Release

A Slice may release when its full Product Acceptance Contract is production-grade
and every enabled Capability has passed Gate E. Enablement may expand gradually
without rewriting implementation.

## Gate V1 — Product Complete

V1 is complete when all V1-required Slices are integrated, production-grade and
operable, with cross-domain identity/metrics/workflow/AI/audit/recovery intact.
Business sales/profit improvement is measured after release and does not block
this capability Gate.

## Evidence classes

```text
UNIT_OR_STATIC
REAL_DATABASE
INTEGRATION_SERVICE
BROWSER_E2E
REAL_PROVIDER_OR_EXTERNAL_SYSTEM
CONTROLLED_PRODUCTION
DISASTER_RECOVERY_DRILL
```

Claims must name their evidence class. Lower classes cannot masquerade as real
provider or production proof.

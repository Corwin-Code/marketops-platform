# Controller Review Standard v2 — Risk-Driven Production Assurance

## 1. Purpose

The Controller independently tests whether a Product/Slice Contract or its actual
implementation is production-grade and faithful to Owner intent. Review effort is
proportional to irreversible risk and executable evidence, not to the number of
workflow transitions.

## 2. Mandatory review dimensions

For every applicable major Gate, record a result for:

1. **Product outcome and scope** — the observable user/business result, non-goals
   and no hidden scope substitution.
2. **Source and authority** — facts, decisions, external evidence, sources of
   truth, writers and module ownership.
3. **Data and migration** — identity, time, money, Confidence, Raw/Ledger,
   idempotency, late data, forward migration and rollback.
4. **State and concurrency** — explicit transitions, leases/fencing, retries,
   duplicate/unknown outcomes and crash windows.
5. **Security and privacy** — authentication, authorization, Secrets, PII,
   external AI, least privilege, audit and abuse boundaries.
6. **AI correctness** — deterministic facts, evidence-linked Fact/Inference/
   Recommendation/Unknown, provider/data controls and no authorization bypass.
7. **Controlled execution** — Policy/Guardrail, Approval, Outbox, idempotency,
   Readback, restore/compensation, Kill Switch and Pilot scope.
8. **User experience** — complete operational journey, Freshness/Confidence,
   safe errors, accessibility and browser evidence.
9. **Operations and recovery** — metrics, alerts, backlog, runbooks, backup/
   restore, provider failure and manual resolution.
10. **Executable evidence** — unit/property/real DB/contract/replay/browser/real
    provider/DR evidence appropriate to the claim.

Also distinguish explicitly:

```text
artifact/PR quality
Slice completion
V1 Product completion
production Capability enablement
```

No one claim implies another.

## 3. Source-first rule

A Maker summary or chat transcript is never sufficient. Inspect actual source,
migrations, tests, diff, PR, CI and current primary-source/provider evidence.
External facts must include source and last-verified date. Fixture/in-memory proof
must be labeled as such.

Use the dual truth model without collapsing either chain:

```text
Normative Truth: Owner Decision → immutable original Contract + accepted
Amendments → ADR/canonical normative docs
Implementation Fact: runtime/DB/external evidence → migration/schema → exact
source/Git → tests/snapshots
```

Classify a conflict as `IMPLEMENTATION_DEFECT`, `CONTRACT_DEFECT` or
`DOCUMENTATION_DRIFT`. No observed implementation fact silently changes the
Contract, and no normative prose erases contrary runtime evidence.

## 4. Finding contract

Findings use:

```text
BLOCKER       unsafe, wrong product/authority/security/data or irreversible risk
MAJOR         required behavior/evidence missing before the current Gate
MINOR         bounded defect that should be fixed but does not redefine Contract
INFORMATIONAL non-blocking observation or later improvement
```

Each finding cites an exact file/line, diff, test, runtime fact or evidence gap and
states the violated Contract clause and required observable correction.

## 5. Contract and interpretation boundary

An accepted original Contract is permanently byte-frozen. A normative change
requires a separately identified additive Amendment with exact bytes, SHA-256
and Human Owner acceptance. Updating the original and its hash in place is not an
Amendment.

Controller interpretation may clarify only non-expansively. Multiple
interpretations may not accumulate into hidden scope, authority, risk or
Acceptance expansion. When existing normative text is defective, classify
`CONTRACT_DEFECT` and request an Amendment rather than rewriting the Contract by
review comment.

## 6. Gate vocabulary

Use the exact verdicts in `AI_OPERATING_MODEL.md`. No conversational equivalent
creates authorization.

## 7. One-shot Deep Review and Frozen Finding Set

Formal Deep Review performs discovery/falsification once across the complete
transitive Slice surface: source, migrations, tests, UI, external evidence,
operations, security and CI. It emits one complete Frozen Finding Set with:

- reviewed Contract and Amendment paths/SHA-256;
- Base, Head and tree;
- evidence inventory and unavailable-evidence boundary;
- stable finding IDs, severity, exact evidence, violated clause and observable
  correction;
- Finding Set artifact path and SHA-256;
- exact next actor/action.

Codex receives that Frozen Finding Set once as part of the rework contract. A
later miss based on evidence already available and reasonably reviewable at Deep
Review is recorded as `CONTROLLER_REVIEW_COVERAGE_FAILURE`; it does not justify
an endless new discovery round.

## 8. Final Gate is closure verification

Final Gate verifies root-cause closure of every Frozen Finding, implementation of
accepted Amendments, same-class/transitive coverage, absence of test/control
weakening, refreshed regression/runtime evidence and final Contract satisfaction.
It is not an open-ended second discovery review. Reopening requires materially
new, previously unavailable severe evidence, with that evidence and the reopening
reason recorded explicitly.

## 9. Artifact contract

Produce one standalone Controller Review and one standalone Next-action Prompt,
with SHA-256 and exact `NEXT_AUTHORIZED_ACTOR` / `NEXT_ACTION`, for:

- Development Baseline Reset / Decision Request;
- Product or Slice Contract Gate;
- Implementation Deep Review;
- Final PR Gate;
- Bounded Real-Write Verification Authorization;
- Controlled Capability Enablement;
- V1 Product Complete Gate.

A routine read-only analysis, CI observation or same-finding verification does not
require a new full pair unless it changes scope, verdict or authority. A Final
Gate artifact binds the exact Frozen Finding Set rather than creating a second
ordinary Finding Set.

## 10. Owner question discipline

Ask the Owner only when an answer can materially change product behavior, risk,
legal/commercial authority, external account use, irreversible data treatment or
production enablement. Ask one question at a time. Do not escalate normal
engineering judgment.

## 11. Independence

- GPT Controller does not become the primary implementation author it later
  approves.
- Claude does not give itself final approval.
- Codex does not approve its own rework.
- CI does not make business decisions.
- Human Owner retains final merge and production authority.

## 12. No endless micro-design

Once WHAT, hard boundaries, acceptance and stop conditions are sufficient for
safe implementation, authorize implementation. Detailed HOW evolves inside the
implementation and is judged against executable evidence. Insert a Design Gate
only for the explicit Conditional Design Gate triggers.

## 13. Controller Slice Closure and Owner Formal Closure

After applicable merge, release and evidence identity are known, Controller Slice
Closure records technical closure against the exact Contract/Amendments and
Frozen Finding Set. Human Owner Formal Closure then confirms identities,
Owner-only conditional acceptance and absence of a new Owner-only blocking fact.
It is not a third engineering review.

The exact Owner-accepted Closure Snapshot required by
`CLOSURE_SNAPSHOT_STANDARD.md` must be published before the next Slice starts.

## 14. Owner-facing communication

Use readable Chinese by default. Lead with verdict, load-bearing findings,
evidence and exact next action. Do not hide uncertainty or collapse provider,
Slice and whole-product readiness claims.

# Controller Review Standard — 11+1 and Artifact Contract

## 1. Purpose and applicability

This is the mandatory review standard for every substantive Controller Planning,
Design Review, Implementation Review, Pull Request Review and Fix/Rework verdict.
It supplements the stage-specific Quality Gates without changing accepted ADRs,
Human Owner authority or repository protection.

The Controller reads the repository, source requirements, current state, active
Work Package, actual diff, tests and CI appropriate to the stage. A Maker summary
or chat transcript is never sufficient evidence.

## 2. The 11+1 review standard

Every substantive Controller review records a result for all applicable items:

1. **Full repository cross-check.** Cross-check requirements, accepted decisions,
   ADRs, Work Package, current files, diff, tests, evidence, PR and CI rather than
   reviewing a summary in isolation.
2. **Stage target and blocker distinction.** Judge the artifact against its
   current Planning, Design, Implementation or PR target and identify genuine
   production blockers without pretending that a phase result is the whole
   product.
3. **Full production-grade scope.** Require the complete approved scope and its
   failure/security/operations contract; do not approve a minimal vertical slice
   when the Work Package requires a production-grade result.
4. **No in-scope deferred item.** Reject an unhandled requirement that belongs to
   the current authorized scope. A later requirement is acceptable only when its
   ownership and boundary were already outside the current Work Package.
5. **No compromise implementation.** Do not accept placeholders, weakened tests,
   insecure defaults, silent unknown-state coercion or temporary behavior that
   contradicts the approved contract.
6. **Owner decisions only for genuine authority/business blockers.** Do not send
   normal engineering judgment back to the Owner. Escalate only a real business,
   risk, credential, production, legal or authorization choice.
7. **Functional and current documentation.** JavaDoc, comments, runbooks and
   evidence must describe actual behavior and current state rather than restating
   syntax or retaining superseded claims.
8. **Retire deprecated, stale and parallel state.** Remove or explicitly classify
   superseded live language, duplicate runtime authorities and obsolete paths so
   they cannot be mistaken for current truth.
9. **Three global hard rules.** Recheck compromise retirement, functional JavaDoc
   rewrite and production naming using the real validators and evidence; do not
   infer a pass from unrelated checks.
10. **Actionable design without endless micro-design.** Require enough detail to
    implement and verify safely, then issue a concrete verdict and next action
    instead of creating an unbounded sequence of design-only loops.
11. **Standalone review and prompt artifacts.** The review and next-action prompt
    must be understandable without the chat transcript and must bind decisions to
    exact artifacts, versions, SHAs or evidence as applicable.

**+1 — Project-grade distinction.** State separately whether the reviewed result
is project-grade for its approved stage, whether the current phase/work package
is complete, and whether the whole product is production-ready. Never collapse
those three claims.

## 3. Finding and verdict contract

Findings use `BLOCKER`, `MAJOR`, `MINOR` or `INFORMATIONAL` and cite an exact
file/line, artifact identity, test failure or evidence gap. Verdict vocabulary is
the stage-specific vocabulary in `QUALITY_GATES.md` and
`CHATGPT_PROJECT_INSTRUCTIONS.md`.

An approval is bound to the exact artifact and evidence reviewed. A content
change, Base/Head movement, failing Gate, reopened thread or protection weakening
invalidates a prior approval unless the Controller explicitly re-reviews it.

## 4. Artifact Contract

For every substantive Controller Planning, Design Review, Implementation Review,
Pull Request Review or Fix/Rework verdict, the Owner-facing response must produce:

1. one standalone Controller Review `.md` artifact;
2. one standalone Next-action Prompt `.md` artifact;
3. the SHA-256 of each artifact;
4. explicit `NEXT_AUTHORIZED_ACTOR` and `NEXT_ACTION` values.

The Next-action Prompt targets the actor required by current state: Claude for
Design/Implementation, Codex for bounded Fix/Rework/Verify/Git execution, or the
Human Owner for an authority decision. Artifact identity and status must be
recorded without making chat history the source of truth.

## 5. Owner-facing communication

The main Owner-facing response uses natural Chinese and readable Markdown by
default. It must explain the outcome, findings, evidence and next action directly.
Large YAML blocks are prohibited as the primary response format; at most, a short
machine-readable status summary may appear at the end.

No review artifact may contain credentials, Buyer PII, unredacted production
payloads or unsupported claims about Marketplace capability or production
readiness.

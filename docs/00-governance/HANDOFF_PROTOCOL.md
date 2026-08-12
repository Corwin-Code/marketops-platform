# Handoff Protocol

## 1. Controller → Maker

A valid Work Package handoff contains:

- ID, title, phase and risk;
- business outcome;
- Requirement IDs and accepted ADRs;
- scope and non-goals;
- inputs/outputs and failure states;
- acceptance criteria and evidence;
- security, migration, observability and rollback expectations;
- exact authorization state (`DESIGN ONLY` or `APPROVED_FOR_IMPLEMENTATION`).

## 2. Maker design return

Claude returns a design artifact, not a conversational promise. It includes:

- verified technology/platform facts and sources;
- proposed file/module/data/API changes;
- sequence/state/failure model;
- test plan;
- migration/backfill/compatibility plan;
- security/privacy and secret handling;
- observability/recovery;
- assumptions and decision requests.

## 3. Controller design review

Controller issues one exact verdict. `CHANGES_REQUIRED` must be resolved in a new design revision. No code starts before approval.

## 4. Maker implementation return

Claude creates a branch and Draft PR containing only approved scope. The PR body links Work Package, design, ADRs, tests and evidence.

## 5. CI + Controller PR review

Controller inspects actual diff and evidence. Findings are severity-labeled and traceable. CI failures block approval.

## 6. Rework

Claude or the designated rework agent fixes only stated findings. Any necessary scope or architecture change goes back through a Decision Request or design review.

## 7. Merge and synchronization

Human Owner, or the active D-17 Codex execution delegate, merges after an
independent `APPROVE_FOR_HUMAN_MERGE` verdict and all repository gates pass. The
delegate may not supply its own approving verdict or use a bypass. The merged PR
must update Current State, Decision Log, Traceability and Phase Evidence as
applicable.

While Owner Git Workflow Guidance Mode is required, the merge handoff must also
state the current branch/PR/check state, why merge is or is not allowed, the exact
Owner or delegate UI action, and the post-merge local synchronization/cleanup
commands.

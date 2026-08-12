# AGENTS.md — Rework / Verification Agent Contract

This file applies to Codex or any secondary coding/rework agent.

## Role

The rework agent does not redefine requirements or architecture. It receives a bounded list of Controller findings and repairs only those findings while preserving the approved Work Package scope.

## Before changing code

Read, in order:

1. `docs/00-governance/CURRENT_STATE.md`;
2. `docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md`;
3. active Work Package;
4. Controller review verdict and findings;
5. referenced ADRs;
6. current PR diff and failing CI logs.

## Owner Git workflow guidance

When `owner_git_workflow_guidance` is `REQUIRED` in Current State, begin every
task with the complete task-start briefing defined in
`docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md`. Inspect real Git/PR/CI state,
explain the full lifecycle, identify the current step, and narrate each Git write
before and after it. Do not infer that the Owner is familiar from prior success.
Only explicit Human Owner confirmation may disable the mode.

## Rules

- Do not merge or push to `main`.
- Do not silently expand scope or refactor unrelated areas.
- Do not change an accepted ADR without a Decision Request.
- Do not weaken tests, branch gates, security controls or logging merely to make CI pass.
- Do not use credentials or production PII.
- Run and report the exact checks relevant to each repaired finding.
- Mark any finding that cannot be resolved without an Owner decision as `BLOCKED_BY_OWNER_DECISION`.

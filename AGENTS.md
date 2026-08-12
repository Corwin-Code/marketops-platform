# AGENTS.md — Rework / Verification Agent Contract

This file applies to Codex or any secondary coding/rework agent.

## Role

The rework agent does not redefine requirements or architecture. It receives a bounded list of Controller findings and repairs only those findings while preserving the approved Work Package scope.

## Before changing code

Read, in order:

1. `docs/00-governance/CURRENT_STATE.md`;
2. active Work Package;
3. Controller review verdict and findings;
4. referenced ADRs;
5. current PR diff and failing CI logs.

## Rules

- Do not merge or push to `main`.
- Do not silently expand scope or refactor unrelated areas.
- Do not change an accepted ADR without a Decision Request.
- Do not weaken tests, branch gates, security controls or logging merely to make CI pass.
- Do not use credentials or production PII.
- Run and report the exact checks relevant to each repaired finding.
- Mark any finding that cannot be resolved without an Owner decision as `BLOCKED_BY_OWNER_DECISION`.

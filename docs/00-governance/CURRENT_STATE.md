# Current State

```yaml
as_of: 2026-08-17
project: MarketOps Russia
lifecycle_state: EXECUTING_PHASE_0
phase: Phase 0 — Data, Identity & Visibility Foundation
sprint: Sprint 0
controller: GPT-5.6 Sol Pro / current ChatGPT Project
maker: Claude Cowork / Claude Code (initial artifact producer; no authoritative repository writes in current trial)
rework_agent: Mac Codex (authoritative repository writer, rework/fix/verify, delegated Git execution)
active_work_package: NONE
active_gate: CONTROLLER_PHASE_0_PLANNING
authorization: PLANNING_ONLY
production_write_enabled: false
owner_git_workflow_guidance: REQUIRED
owner_git_workflow_guidance_exit: HUMAN_OWNER_EXPLICIT_CONFIRMATION
owner_git_execution_delegation: ACTIVE
owner_git_execution_delegate: CODEX
owner_git_execution_delegation_scope: PR_READY_AND_MERGE_AFTER_ALL_GATES
owner_git_execution_delegation_exit: HUMAN_OWNER_EXPLICIT_REVOCATION
```

## Approved design of record

```text
Controller design verdict: APPROVED_FOR_IMPLEMENTATION
Source reviewed Design v1.3 artifact SHA-256: 6dcb59abbee843fd93950edb6dff7bb052b213b17279c36884d167e844c89bd4
Source design reviewed repository base: acccc6172cdad626844d99d085eabcc8fb2381ca
Java root package: com.mimococo.marketops
Canonical design: docs/02-architecture/designs/WP-P0-001-foundation-design.md
```

## Completed

- The complete WP-P0-001 C1–C10 foundation implementation was produced by
  Claude, imported into the authoritative repository, reworked by Codex against
  Controller findings, and verified through the protected Pull Request Gate.
- Local configuration generation, prerequisite reporting, backend, frontend,
  PostgreSQL/Flyway, least privilege, architecture boundaries, logging
  redaction, the built-console Ready → Degraded → Ready browser path, supply
  chain inventories and the special-character Fresh Clone are implemented and
  verified. Exact results and immutable CI links are recorded under
  `docs/07-phase-evidence/WP-P0-001/`.
- The eleven stable CI jobs and the active Ruleset constitute the repository
  Gate. WP-P0-001 is complete, its implementation result is verified, and its
  execution authorization is closed. PR #5 completed the independent Controller,
  repository Gate and Human Owner authorization path and was squash-merged to
  `main` as `3473c3670c1fbf5b0f7d40eb70001337146404f7`. Its approved and merged
  tree is `6e060eeb41d17fdbe913af9d47a9a24cc8a2df39`; production writes remain
  disabled.
- Baseline v1.0, the naming baseline, the Controller–Maker–CI–Owner model, the
  repository governance pack and ADRs are established.
- The Public pre-production repository at `Corwin-Code/marketops-platform` is
  protected by Pull Requests, up-to-date checks, conversation resolution,
  required CI, Secret Scanning, Push Protection and Dependabot controls.
- OQ-002 and OQ-003 are resolved: Java/Maven use
  `com.mimococo.marketops`; primary development is macOS with a
  Docker-compatible Compose v2 runtime.
- No in-scope deferred item or compromise implementation remains in WP-P0-001.
- Under D-17, Codex retains only bounded mechanical Git execution authority;
  independent Controller and Human Owner authority remain unchanged.

## Not completed and not claimed

- The whole MarketOps Russia product is not production-ready. WP-P0-001 is only
  the repository, governance, CI, database and health-console foundation.
- Marketplace clients, credentials, production data, authentication,
  authorization, business/domain tables, deployment artifacts and external
  platform writes remain absent by design and belong to later Work Packages.
- Repository conversion back to Private and security-control revalidation remain
  mandatory at real production go-live, or earlier before confidential material,
  under D-15. This continuing project control is not deferred WP-P0-001 scope.

## Active objective

Run independent Controller Phase 0 planning, select the next bounded Work Package
from the approved backlog, and establish a new design/authorization Gate before
any implementation begins. Do not extend WP-P0-001 or infer authorization for
production or Marketplace writes.

## Temporary trial execution mode

- Claude Cowork / Claude Code may produce reviewable artifacts in a disposable
  workspace but does not write the authoritative repository, push, change
  Rulesets or merge in the current trial.
- Mac Codex is the authoritative local repository writer and may import, rework,
  verify, commit, push and maintain Draft Pull Requests. It may execute a merge
  only under active D-17 delegation, an independent Controller merge verdict,
  every repository/project Gate and separate Human Owner authorization.

This is a temporary execution mode, not an accepted permanent operating-model
decision.

## Owner workflow guidance

Every task starts with the briefing in
`docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md`. Only explicit Human Owner
confirmation can disable the mode.

## Current blockers / Owner inputs

None block Controller Phase 0 planning. Business, credential and production
questions remain governed by `docs/00-governance/OPEN_QUESTIONS.md` and the Work
Package that eventually brings each question into scope.

## Next authorized action

```text
Independent Controller performs Phase 0 planning for the next Work Package,
using the completed WP-P0-001 foundation and the canonical backlog as inputs.
```

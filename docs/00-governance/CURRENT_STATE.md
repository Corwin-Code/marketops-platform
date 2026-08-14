# Current State

```yaml
as_of: 2026-08-14
project: MarketOps Russia
lifecycle_state: EXECUTING_PHASE_0
phase: Phase 0 — Data, Identity & Visibility Foundation
sprint: Sprint 0
controller: GPT-5.6 Sol Pro / current ChatGPT Project
maker: Claude Cowork / Claude Code (artifact producer; no authoritative repository writes in current trial)
rework_agent: Mac Codex (authoritative repository writer, rework/fix/verify, delegated Git execution)
active_work_package: WP-P0-001
active_gate: Pull Request Gate — WP-P0-001 Implementation
authorization: APPROVED_FOR_IMPLEMENTATION
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

- Baseline v1.0 loaded as the product and engineering baseline.
- Naming recommendation loaded: MarketOps Russia / `marketops-platform` / `marketops-server` / `marketops-console`.
- Controller–Maker–CI–Owner operating model established.
- Repository bootstrap/governance pack created.
- Initial ADRs, Phase 0 backlog, traceability seed and WP-P0-001 design prompt created.
- Public pre-production repository created at `Corwin-Code/marketops-platform`, connected as `origin`, and seeded on `main`.
- Initial GitHub Actions job `governance` passed for bootstrap commit `3b35977`.
- Human Owner accepted temporary Public visibility under D-15; conversion back to Private is required when real production go-live is reached, or earlier before confidential business material.
- `main-governance` Ruleset is active: PR required, approving reviews `0`, `governance` required, branch up-to-date required, conversations resolved, and deletion/force-push blocked.
- Secret Scanning, Push Protection and Dependabot security updates are enabled for the Public repository.
- Public/G0 baseline merged through PR #1 at `09380d9`.
- Owner Git workflow guidance and bounded D-17 delegation merged through PR #2;
  Current State reconciliation merged through PR #3.
- Owner Git Workflow Guidance Mode is required under D-16 until explicit Human Owner confirmation disables it.
- WP-P0-001 design reviewed and approved by the Controller; the canonical functional
  design is recorded at `docs/02-architecture/designs/WP-P0-001-foundation-design.md`.
- OQ-002 resolved: the Java root package and Maven groupId are `com.mimococo.marketops`.
- OQ-003 resolved: the primary development OS is macOS; local containers use a
  Docker-compatible CLI with Compose v2 support, without mandating a runtime vendor.
- Gate G0 is complete when this design-approval and authorization transition is
  merged.
- The project is executing Phase 0 under the Pull Request Gate.
- Under D-17, Codex is temporarily delegated mechanical PR Ready/merge execution
  only after all repository gates and an independent Controller verdict pass; the
  Human Owner retains authorization/revocation, business, credential and
  production authority.

## Not completed

- WP-P0-001 product implementation has not started. Implementation is authorized
  and proceeds only through the Pull Request Gate.
- The WP-P0-001 C1-C10 implementation artifact has not yet been produced or
  imported into the authoritative repository.
- No Ozon/WB credential, account or production data has been introduced.

## Active objective

Execute Phase 0 by completing the production-grade WP-P0-001 implementation and
its required evidence under the Pull Request Gate. The result must be a
reproducible backend/frontend/database foundation with deterministic CI and
verified Public-repository security controls.

## Temporary trial execution mode

- Claude Cowork / Claude Code reads and cross-checks the repository, performs
  Design, completes the initial implementation in a disposable/local workspace,
  and produces reviewable artifacts, patches or bundles. In the current trial it
  does not write the authoritative repository, push, open Pull Requests, change
  Rulesets or merge.
- Mac Codex is the authoritative local repository writer. It imports patches or
  bundles, cross-checks the repository, performs local Rework/Fix/Verify, commits,
  pushes and maintains Draft Pull Requests. It may execute a gated merge only
  under active D-17 delegation, explicit Owner authorization and every required
  repository/project gate.

This describes the temporary execution mode for the current trial; it is not an
accepted long-term operating-model decision.

## Owner workflow guidance

Every task must start with the briefing defined in
`docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md`. The active agent must explain
the complete branch/PR/CI/merge lifecycle, locate the current task within it and
guide the Owner through the next Git step. Do not infer familiarity from prior
success; only explicit Human Owner confirmation can disable this mode.

## Current blockers / Owner inputs

None block the start of WP-P0-001 implementation. Later Work Packages retain
their own open business, credential and production questions in
`docs/00-governance/OPEN_QUESTIONS.md`.

## Next authorized action

```text
After the C0 design approval and authorization-state transition is merged, Claude
produces the complete WP-P0-001 C1-C10 initial implementation artifact against the
newly merged `main`; Codex later imports, verifies, fixes, commits, pushes, and
opens or maintains the Draft implementation Pull Request.
```

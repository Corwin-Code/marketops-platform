# Current State

```yaml
as_of: 2026-08-12
project: MarketOps Russia
lifecycle_state: INITIATING
phase: Phase 0 — Data, Identity & Visibility Foundation
sprint: Sprint 0
controller: GPT-5.6 Sol Pro / current ChatGPT Project
maker: Claude Web / Claude Code
rework_agent: Codex (inactive until explicitly enabled)
active_work_package: WP-P0-001
active_gate: G0 — Repository & Collaboration Foundation
authorization: DESIGN_ONLY
production_write_enabled: false
owner_git_workflow_guidance: REQUIRED
owner_git_workflow_guidance_exit: HUMAN_OWNER_EXPLICIT_CONFIRMATION
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
- Public/G0 baseline merged through PR #1; local and remote `main` are synchronized at `09380d9`.
- Owner Git Workflow Guidance Mode is required under D-16 until explicit Human Owner confirmation disables it.

## Not completed

- Claude Project and repository access have not yet been configured.
- WP-P0-001 design has not yet been produced or reviewed.
- No backend, frontend, database or product code has been authorized.
- No Ozon/WB credential, account or production data has been introduced.

## Active objective

Pass Gate G0 and approve WP-P0-001 design. The result must be a protected Public pre-production repository, deterministic governance CI, Public-repository security controls and an approved implementation plan for the minimal production-grade project skeleton.

## Owner workflow guidance

Every task must start with the briefing defined in
`docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md`. The active agent must explain
the complete branch/PR/CI/merge lifecycle, locate the current task within it and
guide the Owner through the next Git step. Do not infer familiarity from prior
success; only explicit Human Owner confirmation can disable this mode.

## Current blockers / Owner inputs

| ID | Input | Blocking point | Status |
| --- | --- | --- | --- |
| OQ-002 | Actual company-controlled Java namespace, e.g. `com.<company>.marketops` | Before backend implementation | OPEN |
| OQ-003 | Primary developer OS and local container runtime | Before finalizing bootstrap commands | OPEN |
| OQ-004 | Whether Codex is enabled as a formal rework agent | Before first rework cycle | OPEN |

## Next authorized action

```text
Configure Claude Build Studio and run the WP-P0-001 DESIGN prompt with Owner Git
Workflow Guidance Mode active. Do not implement yet.
```

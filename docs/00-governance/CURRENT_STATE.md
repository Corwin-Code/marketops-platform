# Current State

```yaml
as_of: 2026-08-19
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
Role: prior approved WP-P0-001 foundation provenance; not the active design target
Controller design verdict: APPROVED_FOR_IMPLEMENTATION
Source reviewed Design v1.3 artifact SHA-256: 6dcb59abbee843fd93950edb6dff7bb052b213b17279c36884d167e844c89bd4
Source design reviewed repository base: acccc6172cdad626844d99d085eabcc8fb2381ca
Java root package: com.mimococo.marketops
Canonical design: docs/02-architecture/designs/WP-P0-001-foundation-design.md
```

## Completed WP-P0-002 design provenance

```text
Work Package: WP-P0-002 — Organization, Store, Warehouse & Credential Metadata
Work Package record: docs/03-work-items/WP-P0-002-organization-store-warehouse-credential-metadata.md
Historic design verdict: APPROVED_FOR_IMPLEMENTATION
Current execution authorization: CLOSED
Implementation result: VERIFIED
Canonical design: docs/02-architecture/designs/WP-P0-002-organization-store-warehouse-credential-metadata-design.md
Controller design verdict: APPROVED_FOR_IMPLEMENTATION
Approved Design v1.2 artifact SHA-256: 3e524c666e56b3d5fdecd6e2098a22d1bd9fd88711dd9c524858ca0cdd3859b2
```

The completed WP-P0-001 design remains foundation provenance. WP-P0-002 was
implemented only under the Controller verdict on the exact Design v1.2 artifact
pinned above. That implementation authority is now closed; the historic Design
verdict is provenance and does not authorize further implementation.

## Prior closed planning transition — historical provenance

Immediately before this Design activation, accepted `main`
`489f151ea0f86e65793f1eed27def1ffcfd0bfdb` closed WP-P0-001 and exposed the
following canonical transition:

```text
active_work_package: NONE
active_gate: CONTROLLER_PHASE_0_PLANNING
authorization: PLANNING_ONLY
```

This block is immutable historical provenance for the completed WP-P0-001 Gate.
It is superseded as live runtime state by the leading YAML in this file and must
not be interpreted as current authorization or a parallel state source.

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
- F-17 post-merge governance closure was independently reviewed, authorized and
  squash-merged through PR #8 as
  `489f151ea0f86e65793f1eed27def1ffcfd0bfdb`; its tree is
  `d66049fb72ed9cee28723b1e51ca42138cce1434`.
- The WP-P0-002 Design passed independent Controller review at v1.2 with the
  verdict `APPROVED_FOR_IMPLEMENTATION`; the approved artifact is canonical at
  `docs/02-architecture/designs/WP-P0-002-organization-store-warehouse-credential-metadata-design.md`
  and byte-pinned by the SHA-256 above.
- The WP-P0-002 technical implementation at Head
  `28d50134bbd272dc4cc9335315841a526bb819c5`, tree
  `30de068598341e545782b0bd833da94838ea22c6`, and tested merge
  `0efda272211f91aecdc7cf614744e9ca4a576677` passed independent Controller
  technical re-review. Its implementation result is `VERIFIED`, and the
  implementation authorization is closed.
- WP-P0-002 Requirement → Test → Evidence and all sixteen Work Package
  acceptance criteria are committed under `docs/07-phase-evidence/WP-P0-002/`.
  `ADM-001` is verified to its authorized FULL boundary; PARTIAL source
  requirements remain `ACTIVE_CONTROL` with their later owners explicit.

## Not completed and not claimed

- The whole MarketOps Russia product is not production-ready. WP-P0-001 provides
  the repository, governance, CI, database and health-console foundation, while
  WP-P0-002 provides the verified metadata-domain foundation; later product and
  runtime capabilities remain open.
- Real Marketplace clients and verified platform Capability population,
  plaintext or production credentials and Secret retrieval, production data,
  runtime authentication/authorization, PIM/Order/Inventory/Return/Finance fact
  tables, deployment artifacts and external platform writes remain absent by
  design and belong to later Work Packages.
- PR #10 remains Draft and not merged while this governance/traceability closure
  candidate awaits final independent Controller re-review. The verified
  technical implementation and committed acceptance evidence do not themselves
  authorize Ready, merge or a change to `main`.
- Repository conversion back to Private and security-control revalidation remain
  mandatory at real production go-live, or earlier before confidential material,
  under D-15. This continuing project control is not deferred WP-P0-001 scope.

## Active objective

The Controller performs the final review of the WP-P0-002 governance and
traceability closure on Draft PR #10. If that exact closure Head and tested merge
are accepted and later merged with separate Human Owner authorization, live work
returns to Controller Phase 0 planning. WP-P0-003 remains DRAFT and is not
activated by this closure. Production writes remain disabled.

## Temporary trial execution mode

- Claude Cowork / Claude Code may produce reviewable artifacts in a disposable
  workspace but does not write the authoritative repository, push, change
  Rulesets or merge in the current trial.
- Mac Codex is the authoritative local repository writer and may perform bounded
  rework, verify, commit, push and maintain Draft Pull Requests. It may execute a merge
  only under active D-17 delegation, an independent Controller merge verdict,
  every repository/project Gate and separate Human Owner authorization.

This is a temporary execution mode, not an accepted permanent operating-model
decision.

## Owner workflow guidance

Every task starts with the briefing in
`docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md`. Only explicit Human Owner
confirmation can disable the mode.

## Current blockers / Owner inputs

OQ-101 topology input is sufficient for metadata Design, while actual Account,
Store and Warehouse inventory remains open for onboarding/acceptance. OQ-005,
OQ-006 and OQ-102 do not block metadata Design but continue to block later
runtime IAM, Secret retrieval and verified platform Capability work respectively.
No Secret or production data is requested.

## Next authorized action

```text
The independent GPT Controller reviews the exact WP-P0-002 closure Head and
tested merge on Draft PR #10. No Ready action or merge is authorized without a
future APPROVE_FOR_HUMAN_MERGE verdict and separate Human Owner authorization.
After an authorized merge, the next live action is Controller Phase 0 planning;
WP-P0-003 remains DRAFT. OQ-101/OQ-005/OQ-006/OQ-102 remain open as allocated.
```

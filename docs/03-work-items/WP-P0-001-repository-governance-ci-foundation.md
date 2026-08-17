# WP-P0-001 — Repository, Governance & CI Foundation

## 1. Metadata

| Field | Value |
| --- | --- |
| Status | COMPLETED |
| Historic design verdict | APPROVED_FOR_IMPLEMENTATION |
| Current execution authorization | CLOSED |
| Implementation result | VERIFIED |
| Phase | Sprint 0 / Phase 0 |
| Risk | Medium |
| Controller | GPT-5.6 Sol Pro |
| Maker | Claude Cowork / Claude Code — complete initial implementation artifact |
| Repository writer / Rework | Mac Codex |
| Final merge authorization | Human Owner |
| Merge execution | Human Owner or active D-17 Codex delegate after all gates |
| Proposed branch | `feat/WP-P0-001-repository-foundation` |
| Target branch | `main` |

## 2. Outcome

Implement the approved, Public pre-production, reproducible and production-grade
monorepo foundation under D-15. A fresh clone must build, test and run the complete
WP-P0-001 backend/frontend/database foundation with deterministic CI, without
Marketplace credentials or production data. At real production go-live, or
earlier before confidential business material, the repository must return to
Private and all repository/security controls must be revalidated.

## 3. Source Requirements

- D-03: Modular Monolith + PostgreSQL Worker.
- D-10: Phase Gate before automation expansion.
- D-15: Public repository during pre-production, followed by Private conversion
  and repository/security-control revalidation at real production go-live, or
  earlier before confidential business material.
- D-16: Owner Git Workflow Guidance Mode remains active at every task start until
  explicit Human Owner confirmation disables it.
- D-17 / DR-0001: Codex may temporarily execute gated PR Ready/merge operations;
  independent Controller review, Ruleset/CI and Owner revocation authority remain.
- HR-06: Secret and least-privilege control.
- Baseline Section 7: Java 21, Spring Boot, PostgreSQL, Flyway, React + TypeScript, Docker, S3-compatible storage and observability direction.
- Baseline Sections 18–19: test pyramid, CI Gate, structured logging and runbook expectations.
- Baseline Sections 21 and 23: Definition of Ready, Definition of Done and Phase Gates.
- ADR-0001 and ADR-0004.

## 4. Scope

Implementation must deliver:

1. monorepo structure for `marketops-server`, `marketops-console`, infrastructure, fixtures, scripts and docs;
2. current supported versions of Java/Spring Boot/Maven or Gradle, Node/package manager, React toolchain, PostgreSQL, Flyway and Docker, with official-source verification date;
3. Spring Boot application with health/readiness endpoint and enforced package/module boundaries under `com.mimococo.marketops`;
4. React + TypeScript application shell displaying `MarketOps Russia` and backend connectivity/data-health state;
5. PostgreSQL local service and Flyway execution, including the Baseline schemas `iam`, `platform`, `raw`, `staging`, `core`, `ledger`, `mart`, `ops` without domain tables beyond this WP;
6. Docker Compose or equivalent local orchestration;
7. configuration/secret separation with `.env.example` containing names only, never real values;
8. deterministic CI jobs for governance, backend quality, frontend quality, migration validation and architecture boundary checks;
9. local developer commands, initial logging/health conventions and troubleshooting notes;
10. update of Current State, traceability and implementation evidence.

## 5. Non-goals

- no Ozon or Wildberries API client;
- no real credential or Secret Manager integration;
- no production deployment;
- no domain tables for product/order/inventory/finance;
- no authentication/authorization implementation beyond documenting the future boundary;
- no Marketplace write capability;
- no dashboard feature beyond the required health shell;
- no Kafka, Kubernetes, microservice split or broad design-system work.

## 6. Design Deliverables

The approved canonical design at
`docs/02-architecture/designs/WP-P0-001-foundation-design.md` defines:

- technology lines and support posture;
- pinning rules and official-source refresh policy;
- implementation evidence requirements for exact resolved versions and verification dates;
- proposed repository tree and naming;
- backend build/module/package plan using `com.mimococo.marketops`;
- frontend build/package plan and rationale;
- PostgreSQL/Flyway schema bootstrap and migration numbering strategy;
- local run commands and dependency prerequisites;
- CI workflow/job names, triggers, permissions, caches and required checks;
- architecture boundary testing approach;
- configuration, Secret, PII and fixture policy;
- test matrix and expected evidence;
- observability/health/logging baseline;
- upgrade/rollback and cleanup plan;
- risks, assumptions and accepted constraints.

## 7. Acceptance Criteria

Implementation will be accepted only when all applicable criteria pass:

1. fresh clone builds without undocumented manual file edits;
2. Java 21 backend compiles and unit/smoke tests pass;
3. frontend install, lint, type check, test and production build pass;
4. PostgreSQL starts locally and Flyway validates/applies bootstrap schemas idempotently on a clean database;
5. backend exposes health/readiness without leaking configuration;
6. frontend shell shows product identity, environment/build metadata and explicit backend/data-health state;
7. CI jobs have stable unique names and pass on a PR;
8. architecture test prevents an example prohibited module dependency pattern;
9. no credential, private key, buyer PII or unredacted production payload is present;
10. README, developer setup, troubleshooting, Current State and traceability are updated;
11. exact commands and CI links/logs are supplied as evidence;
12. no change exceeds the Work Package scope.

## 8. Required Evidence

- design document approved by Controller;
- PR link and commit SHA;
- full list of changed files;
- local command transcript with pass/fail status;
- CI run evidence for every required job;
- Flyway clean-database evidence;
- backend health response and frontend smoke evidence;
- secret/PII review statement;
- known limitations and explicit confirmation that no in-scope item is deferred;
- updated traceability and Current State.

## 9. Risks and Constraints

- Exact tool versions may have changed and must be verified against current official sources.
- The Java namespace and Maven groupId are resolved as `com.mimococo.marketops`.
- Solo development cannot require an approval from another human in GitHub;
  independent GPT review and required CI therefore remain external/technical
  gates, while the Human Owner or active D-17 delegate executes the merge.
- GitHub plan/security-feature availability may differ; unavailable premium controls must be recorded, not silently assumed.
- Public visibility makes source, history, Issues, PRs and Actions evidence public;
  confidential business material is prohibited, and the Private conversion before
  production must include repository/security-control revalidation.
- The Owner workflow briefing is a teaching control, not an additional GitHub
  approving-review requirement. D-17 delegates only mechanical merge execution,
  not Owner authorization or independent Controller approval.
- The bootstrap must remain simple; adding frameworks without a current requirement requires a Decision Request.

## 10. Controller Gate

Historic design verdict:

```text
APPROVED_FOR_IMPLEMENTATION
```

Current execution authorization:

```text
CLOSED
```

Implementation result:

```text
VERIFIED
```

The design verdict is immutable provenance for the implementation that was
authorized. It is not current permission to change the completed Work Package.
PR #5 satisfied independent Controller review, all repository/project Gates and
separate Human Owner merge authorization, then was squash-merged to `main` as
`3473c3670c1fbf5b0f7d40eb70001337146404f7`. Its merged tree
`6e060eeb41d17fdbe913af9d47a9a24cc8a2df39` exactly matches the approved source
tree. Any further implementation requires a newly active Work Package and its
own authorization Gate. Nothing here authorizes direct push to `main`,
production enablement, Marketplace credentials or production data.

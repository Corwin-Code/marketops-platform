# WP-P0-001 — Repository, Governance & CI Foundation

## 1. Metadata

| Field | Value |
| --- | --- |
| Status | READY_FOR_DESIGN |
| Authorization | DESIGN ONLY |
| Phase | Sprint 0 / Phase 0 |
| Risk | Medium |
| Controller | GPT-5.6 Sol Pro |
| Maker | Claude Web / Claude Code |
| Final merge authority | Human Owner |
| Proposed branch | `feat/WP-P0-001-repository-foundation` |
| Target branch | `main` |

## 2. Outcome

Create an approved implementation design for a Public pre-production, reproducible, production-oriented monorepo foundation under D-15. When real production go-live is reached, or earlier before confidential business material, the repository must return to Private and all repository/security controls must be revalidated. After later implementation, a fresh clone must be able to build, test and run a minimal backend/frontend/database slice with deterministic CI, without platform credentials or production data.

## 3. Source Requirements

- D-03: Modular Monolith + PostgreSQL Worker.
- D-10: Phase Gate before automation expansion.
- D-15: Public repository during pre-production, followed by Private conversion
  and repository/security-control revalidation at real production go-live, or
  earlier before confidential business material.
- HR-06: Secret and least-privilege control.
- Baseline Section 7: Java 21, Spring Boot, PostgreSQL, Flyway, React + TypeScript, Docker, S3-compatible storage and observability direction.
- Baseline Sections 18–19: test pyramid, CI Gate, structured logging and runbook expectations.
- Baseline Sections 21 and 23: Definition of Ready, Definition of Done and Phase Gates.
- ADR-0001 and ADR-0004.

## 4. Scope

The design must cover the later implementation of:

1. monorepo structure for `marketops-server`, `marketops-console`, infrastructure, fixtures, scripts and docs;
2. current supported versions of Java/Spring Boot/Maven or Gradle, Node/package manager, React toolchain, PostgreSQL, Flyway and Docker, with official-source verification date;
3. minimal Spring Boot application with health/readiness endpoint and package/module boundary starter;
4. minimal React + TypeScript application shell displaying `MarketOps Russia` and backend connectivity/data-health state;
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
- no dashboard feature beyond a minimal shell/health signal;
- no Kafka, Kubernetes, microservice split or broad design-system work.

## 6. Design Deliverables

Claude must return, without editing product code:

- `Version Matrix`: chosen versions, support status, official references and last-verified date;
- proposed repository tree and naming;
- backend build/module/package plan, including placeholder for `com.<company>.marketops` decision;
- frontend build/package plan and rationale;
- PostgreSQL/Flyway schema bootstrap and migration numbering strategy;
- local run commands and dependency prerequisites;
- CI workflow/job names, triggers, permissions, caches and required checks;
- architecture boundary testing approach;
- configuration, Secret, PII and fixture policy;
- test matrix and expected evidence;
- observability/health/logging baseline;
- upgrade/rollback and cleanup plan;
- risks, assumptions, alternatives and Decision Requests.

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
- known limitations and deferred items;
- updated traceability and Current State.

## 9. Risks and Constraints

- Exact tool versions may have changed and must be verified against current official sources.
- The Java namespace cannot be finalized until Owner supplies a controlled company domain.
- Solo development cannot require an approval from another human in GitHub; independent GPT review and required CI therefore remain external/technical gates, while Human Owner merges.
- GitHub plan/security-feature availability may differ; unavailable premium controls must be recorded, not silently assumed.
- Public visibility makes source, history, Issues, PRs and Actions evidence public;
  confidential business material is prohibited, and the Private conversion before
  production must include repository/security-control revalidation.
- The bootstrap must remain simple; adding frameworks without a current requirement requires a Decision Request.

## 10. Controller Gate

Current verdict:

```text
AUTHORIZED_TO_START_DESIGN
```

Implementation is prohibited until a reviewed design receives exactly:

```text
APPROVED_FOR_IMPLEMENTATION
```

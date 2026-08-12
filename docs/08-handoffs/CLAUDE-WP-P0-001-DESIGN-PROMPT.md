# Prompt for Claude — WP-P0-001 Design Only

You are working in the MarketOps Russia repository as the Designer for `WP-P0-001 — Repository, Governance & CI Foundation`.

## Mandatory first actions

Read in this order:

1. `CLAUDE.md`
2. `docs/00-governance/CURRENT_STATE.md`
3. `docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md`
4. `docs/02-architecture/adr/ADR-0001-modular-monolith-and-technology-baseline.md`
5. `docs/02-architecture/adr/ADR-0004-ai-maker-checker-development-model.md`
6. `docs/01-requirements/baseline-v1.0-cn.md`, especially Sections 5, 7, 14, 15, 18, 19, 20, 21 and 23
7. the current repository tree and governance workflow

## Authorization boundary

This task is `DESIGN ONLY`.

Do not edit product code, build files, migrations, Docker files or CI workflows. Do not create a branch or PR yet. Do not introduce credentials or production data.

## Required research

Verify the current supported/stable versions and compatibility of the proposed Java/Spring Boot build, Node/package manager/React toolchain, PostgreSQL, Flyway, Docker and GitHub Actions using current official primary sources. Record source title and last-verified date. Do not rely on memory for current versions.

## Required design output

Produce one structured design document containing:

1. Executive decision summary;
2. Fact / Inference / Proposal / Unknown separation;
3. Version and compatibility matrix;
4. exact proposed repository tree;
5. backend project/build/package/module plan;
6. frontend project/build/package plan;
7. PostgreSQL/Flyway bootstrap-schema and migration strategy;
8. local environment and one-command-or-documented-command startup flow;
9. CI workflows, unique job names, triggers, permissions, caches and required checks;
10. architecture boundary test strategy;
11. health/readiness, structured logging and build metadata behavior;
12. config/Secret/PII/fixture policy;
13. test matrix mapped to WP acceptance criteria;
14. rollback/cleanup/upgrade strategy;
15. risks, alternatives and Decision Requests;
16. an implementation plan broken into small commits;
17. a complete acceptance/evidence checklist.

Do not claim implementation success. End with exactly one requested Controller verdict:

```text
REQUESTED_VERDICT: REVIEW_WP_P0_001_DESIGN
```

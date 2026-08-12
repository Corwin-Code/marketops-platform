# Project Charter — MarketOps Russia

## 1. Identity

| Field | Value |
| --- | --- |
| Formal Chinese name | 俄罗斯 Marketplace 运营与决策平台 |
| Formal English name | Russia Marketplace Operations & Decision Platform |
| Product short name | MarketOps Russia |
| Engineering short name | marketops |
| Proposed monorepo | marketops-platform |
| Baseline | Development Baseline v1.0, 2026-08-06 |
| Current phase | Sprint 0 / Phase 0 |
| Status | INITIATING |

## 2. Mission

Build an internal, production-grade and auditable operations and decision platform for a Russian local Marketplace business. The platform unifies Ozon, Wildberries, warehouse, procurement, cost, advertising, promotion, return and settlement facts; establishes traceable SKU-level operating truth; and turns verified exceptions into recommendations, tasks, approvals and later controlled execution.

## 3. Phase 0 objective

Phase 0 establishes Data, Identity & Visibility Foundation:

- Organization, account, store and warehouse identity;
- Product Variant and platform-ID mapping;
- immutable Raw evidence and replay;
- historical backfill and data quality;
- basic order, stock, return and cost visibility;
- Daily Business Report v1;
- credential metadata governance without exposing secrets.

## 4. In scope

- Ozon-first architecture and first end-to-end read vertical slice;
- Wildberries parallel read foundation and unified analysis model;
- Java 21 / Spring Boot / PostgreSQL / Flyway / React + TypeScript;
- Modular Monolith and PostgreSQL Task/Outbox Worker;
- Docker-based local and controlled deployment foundation;
- traceability from Requirement to Evidence;
- Maker–Checker AI collaboration with deterministic CI.

## 5. Out of scope for the first phase

- generic external SaaS;
- full ERP, WMS, statutory accounting or tax replacement;
- browser automation, scraping or unpublished platform endpoints;
- unapproved automatic price, stock, order, promotion or ads writes;
- Kafka, Kubernetes, microservices or large-scale real-time streaming;
- treating third-party market estimates as financial truth;
- publishing AI-generated Russian content without authorized native review.

## 6. Fixed Owner decisions

- D-01: dual-platform architecture; Ozon end-to-end first; WB Read Integration in parallel.
- D-02: no unattended platform writes in the first version.
- D-03: Modular Monolith + PostgreSQL Worker.
- D-04: Raw, Inventory Ledger and Financial Ledger are immutable.
- D-05: Variant / Color / Size / Purchase Batch are required operating granularity.
- D-06: third-party competitor data is trend-only.
- D-07: AI recommends only and holds no platform write credentials.
- D-08: official APIs are the only permitted programmatic Marketplace integration.
- D-09: Metrics and Mapping are versioned.
- D-10: no automation expansion before the relevant Phase Gate passes.

## 7. Governance

| Decision type | Authority |
| --- | --- |
| Business scope, commercial floor, account ownership, high-risk automation | Human Owner |
| Requirement interpretation, Work Package, architecture/quality verdict, Phase Gate | GPT Controller |
| Detailed design, implementation, tests, Draft PR | Claude Maker |
| Bounded repair and verification after findings | Codex/Rework Agent when enabled |
| Build, lint, tests, migration checks, security checks | CI |
| Final merge, secret provisioning, production enablement | Human Owner |

## 8. Source-of-truth policy

Chat history is not the project database. Every accepted decision, current state, requirement mapping, design and evidence must be committed to the repository. Any inconsistency must be raised; no agent may silently reconcile or replace the Baseline.

## 9. Initial success condition

The project may leave `INITIATING` and enter `EXECUTING_PHASE_0` only after Gate G0 passes: repository created, governance files versioned, `main` protected, governance CI required, Claude/ChatGPT instructions installed, and WP-P0-001 design approved.

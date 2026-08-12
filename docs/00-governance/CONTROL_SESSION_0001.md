# Control Session 0001 — Project Initiation

| Field | Value |
| --- | --- |
| Session ID | CTL-2026-08-07-001 |
| Date | 2026-08-07 |
| Controller | GPT-5.6 Sol Pro / current ChatGPT Project |
| Purpose | Start MarketOps Russia from zero and initialize the collaboration loop |
| Inputs | Baseline v1.0; Naming Recommendation; Owner collaboration model |

## Source findings adopted

- The Baseline is the unified PRD + SRS + Solution Blueprint + Traceability baseline and may not be silently changed.
- Phase 0 is Data, Identity & Visibility Foundation.
- Ozon is the first end-to-end delivery line; WB read integration is parallel.
- Write is disabled by default; immutable Raw and Ledgers, idempotency, freshness, audit and phase gates are mandatory.
- The recommended engineering names are MarketOps Russia, `marketops-platform`, `marketops-server` and `marketops-console`.

## Initialization actions completed

- Created project charter and source hierarchy.
- Created AI operating model and explicit verdict vocabulary.
- Seeded current state, decision log, open questions, quality gates and change control.
- Created accepted ADRs for architecture, immutable evidence and controlled writes.
- Created Phase 0 Work Package backlog and traceability seed.
- Created GitHub Issue/PR templates and governance CI.
- Created Claude and ChatGPT Project instructions.
- Created the first design handoff prompt.

## Controller verdict

```text
AUTHORIZED_TO_START_WP-P0-001_DESIGN
```

## Explicitly not authorized

- backend/frontend implementation;
- database DDL beyond design proposal;
- Ozon/WB API connection;
- credential provisioning;
- any production data import;
- any platform write operation.

## Exit condition

This session closes when the repository is created, G0 controls are enabled, and Claude returns a complete WP-P0-001 design for Controller review.

# ADR-0004 — AI Maker–Checker Development Model

- Status: ACCEPTED
- Date: 2026-08-07
- Source: Owner collaboration instruction

## Decision

- GPT-5.6 Sol Pro acts as product/architecture/quality/release Controller.
- Claude Web / Claude Code acts as Designer and primary Implementation Agent.
- CI provides deterministic evidence.
- Human Owner performs final merge and production authorization.
- Codex may be added as a bounded rework/verification agent but cannot approve its own changes.
- High-risk work requires Design Gate before implementation.
- Chat histories are not the source of truth; accepted decisions and state are committed to Git.

## Consequences

Task packets and review verdicts must be explicit. The workflow favors small PRs, independent review and evidence over one-shot generation. The Controller must inspect real artifacts rather than relying on Maker summaries.

# ADR-0004 — AI Maker–Checker Development Model

- Status: ACCEPTED
- Date: 2026-08-07
- Source: Owner collaboration instruction
- Refined by: ADR-0006 — Contract-Governed Vibe Coding

> ADR-0006 refines the operating cadence: high-risk work does not automatically
> require a separate Design-only stage. GPT still defines the Contract and
> independently reviews the actual implementation; Claude may perform Detailed
> Design + Initial Full Implementation continuously, and Codex performs in-scope
> rework/verification without self-approval.

## Decision

- GPT-5.6 Sol Pro acts as product/architecture/quality/release Controller.
- Claude Web / Claude Code acts as Designer and primary Implementation Agent.
- CI provides deterministic evidence.
- Human Owner retains final merge authorization/revocation and production authority.
- Under D-17/DR-0001, Codex may be added as a bounded rework/verification agent
  and may mechanically execute Ready/merge while Current State records an active
  delegation, but it cannot approve its own changes or bypass any gate.
- A separate pre-implementation Design Gate is conditional on material Owner,
  irreversible, authority, security, provider or unbounded data/financial risk;
  otherwise the approved Slice Contract authorizes continuous Detailed Design and
  Initial Full Implementation.
- Chat histories are not the source of truth; accepted decisions and state are committed to Git.

## Consequences

Task packets and review verdicts must be explicit. The workflow favors small PRs,
independent review and evidence over one-shot generation. The Controller must
inspect real artifacts rather than relying on Maker summaries. Delegated merge
execution is downstream of, and cannot replace, the independent Controller gate.

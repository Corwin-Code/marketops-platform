# ADR-0006 — Contract-Governed Vibe Coding with Conditional Design Gates

- Status: ACCEPTED
- Date: 2026-08-26
- Source: DR-0003, D-22, CD-V1-011
- Refines: ADR-0004

## Context

A mandatory Design Approval for nearly every substantive MarketOps task caused
repeated context reconstruction and allowed production code to be classified as
Design evidence without advancing product delivery. Detailed Design and
implementation naturally inform each other during Vibe Coding.

## Decision

The GPT Controller first fixes product outcome, scope, non-goals, authority, hard
invariants, acceptance and stop conditions in a Slice Contract. Claude then
performs Detailed Design and Initial Full Implementation continuously. GPT
performs a source-first Deep Review, Codex performs full in-scope production
rework/fix/verify, and GPT performs the Final Gate.

A separate pre-implementation Design Gate is inserted only for the material risk
triggers listed in `AI_OPERATING_MODEL.md`.

## Consequences

- fewer synchronous approval waits without weaker evidence;
- normal engineering HOW remains Maker freedom;
- Owner questions are reserved for genuine authority/business risk;
- the Controller judges real behavior and tests, not design prose alone;
- Maker–Checker independence and protected merge authority remain unchanged.

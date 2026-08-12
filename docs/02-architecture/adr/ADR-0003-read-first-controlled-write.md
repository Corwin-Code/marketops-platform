# ADR-0003 — Read First, Controlled Write Later

- Status: ACCEPTED
- Date: 2026-08-06
- Source: D-02, D-07, D-08, D-10; HR-05, HR-10

## Decision

All Marketplace write capabilities are disabled by default. Initial delivery performs official API/report reads, diagnostics, recommendations and Dry Runs.

A write capability may be enabled only after its independent chain passes:

```text
Recommendation
→ Deterministic Guardrail
→ Dry Run / Impact Preview
→ Approval / Policy Authorization
→ Idempotent Outbox Command
→ Official Platform API Call
→ Readback Verification
→ Audit + Metric Follow-up
```

AI never receives Marketplace credentials or direct write authority.

## Consequences

The first phases optimize trustworthy decision support and manual operations rather than speed of automation. Each write capability has its own kill switch, approval scope, replay/readback behavior and release evidence.

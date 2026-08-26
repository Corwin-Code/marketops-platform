# ADR-0003 — Read First, Controlled Write Later

- Status: SUPERSEDED_IN_PART
- Superseded by: ADR-0008 — Unified Capability Model and Selective Controlled Write
- Date: 2026-08-06
- Source: D-02, D-07, D-08, D-10; HR-05, HR-10

> DR-0003 / ADR-0008 supersedes only the old version/phase sequencing that kept
> real controlled writes outside V1. This ADR's default-off behavior and complete
> Recommendation → Guardrail → Preview → Approval/Policy → Idempotent Command →
> Official API → Readback → Audit/Outcome chain remain binding.

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

V1 may deliver selected writes by Capability and Pilot Cohort once their complete
chain passes; it does not wait for a legacy global phase sequence.
Each write capability has its own kill switch, approval scope, replay/readback behavior and release evidence.

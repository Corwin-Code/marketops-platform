# ADR-0008 — Unified Capability Model and Selective Controlled Write

- Status: ACCEPTED
- Date: 2026-08-26
- Source: D-19, D-24, OD-V1-002/003/004, CD-V1-002/003/004
- Supersedes in part: ADR-0003 rollout sequencing

## Decision

MarketOps expresses business intent through stable capabilities such as:

```text
PRODUCT_READ
PRICE_READ
PRICE_CHANGE
STOCK_READ
STOCK_CHANGE
ADS_CHANGE
PROMOTION_CHANGE
LISTING_CHANGE
ORDER_ACTION
```

Ozon and Wildberries adapters map these intents to their current official API,
permission, asynchronous state, error, quota and Readback semantics. Platform
implementations need not be mechanically symmetrical.

All real writes preserve ADR-0003's complete controlled chain. V1 may enable a
low-risk action under an Owner pre-authorized Policy; high-risk actions remain
Approval-bound. `PRICE_CHANGE` is the first targeted dual-platform write and is
released through a bounded Pilot Cohort. Other domains may initially produce
Recommendation/Task only.

## Consequences

- one product workflow without pretending platform APIs are identical;
- capability-by-capability verification, Kill Switch and release;
- Ozon progress is not blocked by an unrelated WB capability, and vice versa;
- no capability is marked usable without current primary-source and real-account
  evidence;
- Price-first is a product priority, not a claim that the endpoints are already
  verified.

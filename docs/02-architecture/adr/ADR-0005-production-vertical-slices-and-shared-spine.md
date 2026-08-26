# ADR-0005 — Production Vertical Slices and a Thin Shared Spine

- Status: ACCEPTED
- Date: 2026-08-26
- Source: DR-0003, D-18, D-22, OD-V1-022, CD-V1-010

## Context

Horizontal Work Packages made infrastructure independently reviewable but delayed
any user-visible operating loop. MarketOps requires common identity, ingestion,
Raw, metrics, AI, workflow and command controls, yet building each as a separate
product stage creates serial Gates and weak business feedback.

## Decision

Use Production Delivery Slices as the primary delivery unit. Each Slice produces
one complete user-visible operating capability and builds/extends only the Shared
Spine needed for that capability at production depth.

```text
Vertical Slice First
+ Shared Spine Evolves Once
```

The Shared Spine owns cross-cutting authority; Slices must not duplicate it.
Implementation tranches or PRs may divide transport/review work but share one
Slice Contract and do not create independent product phases.

## Consequences

- earlier bounded production value and real provider feedback;
- business progress reported by released Slices, not percentages of technical
  layers;
- shared foundations stay production-grade and reusable;
- no temporary per-Slice ingestion, metric, policy, command or audit stack;
- later Slices may deepen a domain while reusing thin reads created earlier.

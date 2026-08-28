# ADR-0011: Absence and confidence travel with every figure

```yaml
status: ACCEPTED
date: 2026-08-27
slice: SLICE-V1-001
relates_to: ADR-0002
```

## Context

An automated pricing system sells below cost because somebody, somewhere,
treated a missing input as a zero and the arithmetic kept working. Nothing threw,
nothing alerted, and the figure looked exactly like a real one.

The same failure has a quieter cousin: a figure that is real but stale,
estimated, or built from half the picture, presented beside a confirmed one with
nothing to tell them apart.

## Decision

Two vocabularies travel with every canonical value, from the metric engine to
the screen.

`ValueState` says whether a number exists: `AVAILABLE`, `NOT_AVAILABLE`,
`UNDEFINED`. `ConfidenceState` says how much weight it can carry:
`CANONICAL_CONFIRMED`, `CANONICAL_PENDING_SETTLEMENT`, `ESTIMATED_EXPLAINED`,
`STALE`, `INCOMPLETE`, `CONFLICTED`, `UNKNOWN`.

Exactly one confidence state supports a platform write. The guardrail refuses a
metric that is unavailable and refuses one whose confidence is anything else.

The console has one module that decides how a value is presented, and its rule
is asserted over the whole vocabulary rather than case by case: only a confirmed
value may look confirmed, an unavailable value is absent whatever its confidence
says, and an absence renders as an em dash rather than as zero.

## Consequences

**Missing data blocks rather than distorts.** A recommendation built on an
incomplete picture is refused with `REQUIRED_METRIC_UNAVAILABLE` or
`METRIC_CONFIDENCE_INSUFFICIENT` and names which metric. That is more work for
an operator than a plausible wrong number, and it is the point.

**Operators are told, in text, next to the number.** Not a colour, not a
tooltip — a word, because somebody scanning a list reads and does not hover.

**Amounts stay text end to end.** Parsing a decimal into a JavaScript number
silently rounds it, and a rounded price is a different price.

**A rule that could not answer is as visible as one that fired.** Silence about
a declined rule is how a data problem gets mistaken for a clean bill of health.

**The vocabulary is closed and shared.** A second component deciding for itself
which states are safe would eventually decide differently, and the difference
would show up as a price change nobody meant to approve. Adding an eighth
confidence state means changing one map and watching a test that enumerates the
whole vocabulary fail until the new state has been thought about.

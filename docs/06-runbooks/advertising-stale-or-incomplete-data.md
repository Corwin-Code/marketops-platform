# Advertising data that is stale, incomplete or still being corrected

An advertising case that will not move, or a queue that has gone quiet, is
usually the evidence rather than the calculation. This is how to tell which.

## What you will see

A case whose evidence chip reads **Stale**, **Incomplete** or **Estimated**, and
measures that read `not available` rather than a number. Nothing has failed:
the product is telling you it could not establish something, which is a
different statement from establishing a zero.

The three read differently on purpose:

- **Stale** — the freshest contributing fact is older than the freshness profile
  for this purpose allows. The number exists and is too old to act on.
- **Incomplete** — part of the observation window is missing, so a sum is not
  the sum. `ledger.ad_object_fact.report_window_complete` is false for at least
  one row in the window.
- **Estimated** — the value was derived rather than reported. It is never
  write-grade, so no bid change can be built on it.

## Finding out which fact is behind it

```sql
SELECT f.period_start, f.period_end, f.report_window_complete,
       f.correction_window_open, f.source_time, f.recorded_at
  FROM ledger.ad_object_fact f
 WHERE f.ad_native_object_id = :objectId
   AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                    WHERE later.supersedes_fact_id = f.id)
 ORDER BY f.period_start DESC LIMIT 20;
```

`correction_window_open` is the one people miss. A marketplace that has not
closed its correction window can still restate these numbers, so the product
marks the evidence provisional and refuses to build a write on it. That is not
a defect to route around; it is the window doing its job.

Which purposes are currently unsatisfiable, and why:

```sql
SELECT * FROM core.ad_freshness_purpose_violations(:organizationId, :objectId, now());
```

## What to do

**Do not** widen a freshness profile to make a case move. The profile is a
published, owner-attributed decision with an evidence reference, and editing it
to unblock one case changes the rule for every case.

Do, in this order:

1. Check the acquisition backlog — see `acquisition-backlog.md`. Stale
   advertising facts are most often a read that has fallen behind rather than a
   provider problem.
2. If the window is incomplete, wait for the provider's own reporting window to
   close. `period_end` plus the profile's `expected_publication_lag_minutes` is
   the earliest it can be complete.
3. If it has not resolved by then, treat it as a provider incident and follow
   `advertising-provider-incident.md`.

## What must not happen

An operator must never record a figure by hand to fill a gap in advertising
evidence. `core.fact_provenance` distinguishes a manual entry from a provider
read, and a manually entered advertising fact is not write-grade — so a bid
change built on one is refused at the gate anyway, after somebody has spent an
afternoon on it.

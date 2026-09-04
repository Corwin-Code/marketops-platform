# Advertising work that will not start because something else is running

Two blocks look similar from the queue and mean different things. One says
another intervention holds these products; the other says the organization has
used up how much advertising it may have in flight at once.

## Reservation conflict

The gate reports `RESERVATION_CONFLICT`, or an approval refuses with
`CONCURRENT_INTERVENTION`.

One advertising object carries traffic for many product variants. Two objects
changed at the same time can move the same variants' sales, and afterwards
nobody can say which change did what. So a reservation covers the **affected
set**, and an overlapping one is refused rather than ordered.

Find out what holds it:

```sql
SELECT * FROM ops.ad_overlapping_reservation(:organizationId,
        ARRAY[:variantIds]::uuid[], :excludeObjectId);
```

The answer names the lane. Protection outranks data repair, which outranks
optimization — so an optimization case waiting behind a protection case is the
system working, and the right action is to finish the protection case.

A reservation releases when four conditions all hold, and no sooner:
configuration resolved, no unknown or mismatch open, early observation complete,
no regression open. `ops.release_ad_action_reservation` reads all four from the
row rather than accepting them as arguments, so nobody can release by asserting
what they have not observed.

## Aggregate exposure

The gate reports `AGGREGATE_ENVELOPE_BLOCKED`, or
`AGGREGATE_ENVELOPE_UNRESOLVED` when no envelope is in force at all.

Four axes, each checked independently — no axis lends slack to another:

| Axis | What it bounds |
| --- | --- |
| `max_active_interventions` | how many advertising changes may be live at once |
| `max_unresolved_transmitted_writes` | how many outcomes may be unknown at once |
| `max_cumulative_bid_change_amount` | how much bid movement in a rolling window |
| `reserved_recovery_headroom_count` | slots kept free for compensation |

The recovery headroom is the one to understand. Ordinary work may not consume
it; only a compensation may. Without it, a product that filled its envelope with
new changes would have no room left to undo one that went wrong.

An unresolved envelope is **not** an open one. No envelope in force means no
advertising write, because nobody has said how much exposure is acceptable.

## What to do

Wait, or finish what is running. Do not widen an envelope to unblock a queue:
the envelope is a versioned, owner-attributed decision about how much of the
business may be under simultaneous advertising change, and raising it because
today's queue is long is exactly the decision it exists to prevent.

If unresolved outcomes are what is consuming the envelope, that is the real
problem. See `advertising-unknown-result.md`.

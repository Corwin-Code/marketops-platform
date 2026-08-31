# A card that will not resolve

An availability card can sit in the queue without naming a shortage. That is the
system saying it cannot answer, and the answer is a repair rather than a
replenishment. This page covers the five states an operator meets and what each
one needs.

The rule underneath all of them: a company answer never becomes safe by
default. When a material input is missing the card stays visible and stays
somebody's work, because the alternative is a green card over a real stockout.

## The company child is unresolved and data-blocked

The card carries `COMPANY_SUPPLY_OWNERSHIP_NOT_DECLARED` or a similar blocker.
Some platform-visible stock exists that nobody has classified: it is either the
same physical units the warehouse holds, or genuinely separate stock held at
the marketplace, and the two produce different answers.

Resolve it by publishing a supply ownership declaration for the exact store and
fulfillment mode, stating `MIRRORS_INTERNAL` with the warehouse it mirrors, or
`PHYSICALLY_DISTINCT`. The declaration is effective-dated and evidence-linked;
two active declarations for one store and mode cannot overlap in time, so
correcting one means ending its interval and publishing a successor.

The card resolves on the next recalculation. It does not resolve by anyone
deciding the stock is probably fine.

## The child is policy-blocked

No lead-time and safety version resolves for the variant at any scope, or no
demand-observation version is in force for the organization. There is no
default: a missing policy is not zero lead time and not zero safety days.

Resolve it by publishing the version at the scope that should own it —
variant with supplier and route, supplier, product category, or the
organization default. Publishing requires `SUPPLY_POLICY_MANAGE` and a recent
authentication, because a shortened lead time can clear a queue without a
single unit moving.

## Demand is censored and then blocked

The card reports that every recent window was materially censored: the listing
was unsellable, or there was nothing to sell, or the source stopped publishing.
While a previously eligible answer exists it is carried forward, visibly
downgraded and showing the period it came from. When the bounded carry-forward
period expires the answer becomes data-blocked.

Demand never becomes zero through this path. Resolve it by restoring
observation — repair the feed, or restore the listing — rather than by
supplying a number.

## The child is provisional

The card shows a lower-bound argument rather than a full picture: using only
stock that is owned, fresh and proven distinct, the variant still runs out
inside its horizon. The refused units are listed with the reason each was
refused, and they can only reduce the shortfall, never create it.

A provisional card is actionable now. Clearing the refusal — usually an
ownership declaration or a stale feed — turns it into a confirmed answer, which
may be better or worse than the lower bound but will be the real one.

## Profit evidence is blocked

The card reports `PROFIT_DATA_BLOCKED`. Settled and operational contribution
profit are both unavailable, stale, incomplete or conflicted, so the variant
cannot be placed in the profitable queue and cannot be dismissed from it
either.

Resolve it through the cost and finance-input path that owns the missing
component. The availability queue does not estimate profit to fill the gap.

## The queue has stopped being current

The loop reports one of three named incidents. Each one means the queue an
operator is trusting is behind, which is the only failure that makes every
other page in this runbook misleading.

`RECONCILIATION_SWEEP_OVERDUE` — no full portfolio reconciliation has completed
inside its cadence and grace period. Targeted recalculation may still be
running, so cards are not necessarily stale; what is lost is the safety net
that repairs a dropped trigger. Check that the availability worker is enabled
and running in this environment, look for a sweep stuck in `RUNNING` (only one
per organization is allowed, and a process that died holding one blocks the
next), and close it out before triggering a fresh sweep.

`RECALCULATION_BACKLOG_BEYOND_OBLIGATION` — the oldest unfinished recalculation
has been waiting since its fact was accepted for longer than the response
obligation allows. Read the depth and the age together: a thousand requests
queued a second ago are healthy and one queued an hour ago is not. Look for
requests repeatedly attempted and failing; past the attempt bound they are
abandoned so one permanently broken variant cannot occupy the queue, and the
sweep still visits it.

`CRITICAL_RESPONSE_HARD_BOUND_BREACHED` or
`CRITICAL_RESPONSE_DISTRIBUTION_TARGET_MISSED` — the response evidence itself
says the promise was not kept. The two latencies are recorded separately, so
read them apart before acting: a large source latency is a marketplace
publishing late and is not this system's incident, while a large internal
latency is. Both are stored per recalculation and can be re-examined; neither
is an aggregate that has already thrown away the detail.

## A dropped trigger

Nothing needs to be diagnosed. The sweep recalculates the portfolio and closes
the requests it covered, which is what turns a lost trigger into a recovered
one rather than a queue that never drains. Confirm the repair by reading the
run's repaired count.

If a trigger is dropped and the sweep is also overdue, treat the sweep as the
incident: the trigger is recoverable and the sweep is what recovers it.

## An acceptance that should no longer hold

Expiry is automatic: the sweep ends the acceptance and reopens the same case
with its journal intact. Everything else is a decision somebody makes — a
materiality increase, a cause or scope change, an evidence conflict, a lost
authority, or the same condition being accepted too often. Invalidating for a
governance failure escalates the case as well as reopening it, because a
governance failure needs a higher authority than the one that let it happen.

No acceptance can be extended by editing it. A new period is a new request,
sized again by the materiality version in force at the time, and refused if the
requester is also its only approver where separation is required.

## What none of these do

None of these states writes anything to a marketplace. This capability has no
stock command, no outbox, no adapter write and no readback, and nothing in this
runbook creates one.

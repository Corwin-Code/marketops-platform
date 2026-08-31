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

## What none of these do

None of these states writes anything to a marketplace. This capability has no
stock command, no outbox, no adapter write and no readback, and nothing in this
runbook creates one.

# Listing containment and re-enablement

Stopping listing work at the right scope, and the slower business of
restarting it.

## Stopping

Anyone holding `LISTING_CONTAINMENT_STOP` (Owner, Ops Lead, Risk Authority,
Tech/Data) stops a listing, a store, a platform, a batch or the whole
organization from the governance tab or
`POST /api/v1/console/listing/governance/containments/stop`, with a cause class,
the role that owns the cause, a reason and an evidence reference. The stop:

- moves every live action in scope to `CONTAINED`; a contained action is never
  revived, it is re-prepared if still wanted;
- closes the description write gate with `SCOPE_CONTAINED`;
- refuses new launches in scope.

An organization-wide stop is the fastest control on the page and needs
nothing more than one person with the scope. When in doubt, stop wider.

## What a stop does not do

It does not release occupations, cancel commands in flight or touch the
platform. A command already `EXECUTING` finishes its readback; its gate stays
closed for the next attempt.

## Re-enabling

Re-enabling needs two different people:

1. a `REPAIR_ATTESTATION` from a person who holds `LISTING_CONTAINMENT_ATTEST`
   and the containment's cause-owner role, with evidence of the repair;
2. a `BUSINESS_CONSENT` from a different person holding
   `LISTING_CONTAINMENT_CONSENT`.

The database refuses the same person giving both halves and refuses the
re-enable while either is missing. Re-enabling sets the containment to
`REENABLED` and reopens the scope for new launches; it does not restore
anything that was contained.

## Isolation

A protection failure at a formal node isolates the listing and every listing
reached through proven dependencies (`isolation-scope` on the governance API).
An unmet target is not a failure and does not isolate; it may trigger the
stop rule at a stop node after maturity.

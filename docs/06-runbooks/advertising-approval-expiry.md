# An approval that ran out before the command went

## What you will see

The write gate reporting `APPROVAL_LEASE_EXPIRED`, or a command sitting in
`PENDING` that a worker will not pick up.

## Why approvals expire at all

An approval authorises one exact change, built from one exact set of facts, at
one moment. The advertising world moves: a bid changes, a variant stops being
sellable, a policy version is superseded. An approval with no expiry would let a
decision taken on Monday's evidence be spent on Friday's world.

The window comes from `core.ad_approval_lease_policy` for the direction, and the
command stores the earlier of that lease and the approval's own scope expiry —
so a lease longer than the approval cannot extend an authority nobody granted.

## What to do

**Do not** extend the approval. There is no supported way to, and the reason is
the point: the decision was about facts that are now older than somebody agreed
they could be.

Re-decide instead:

1. Open the advertising case again. The calculation will have refreshed it, and
   the candidate may now be different — which is the information the expiry was
   protecting.
2. If the case no longer appears, the cause stopped holding. `mart.ad_case`
   records that as a supersession with reason `CAUSE_NO_LONGER_CALCULATED`, and
   nothing needs to be done.
3. If it appears with the same candidate, approve it again. The new approval
   carries a new lease.

## The command that was already created

A command whose approval expired never transmitted; the gate refuses before any
worker takes it. It can be terminated without a provider call:

```sql
SELECT ops.transition_ad_bid_command(:commandId, :fence, :owner,
        'TERMINATED_WITHOUT_PROVIDER_CALL', 'approval_lease_expired', NULL, :correlationId);
```

That state exists precisely so a command that never left can be closed without
anybody wondering later whether it did.

## What to check if this happens often

Short leases against a slow review cycle is a real operating problem, not a
technical one. `core.ad_human_slo_profile.action_minutes` is how long the
product expects a person to take, and `core.ad_approval_lease_policy` is how
long the decision stays spendable. If the first is routinely larger than the
second, one of them is wrong and the Owner has to say which.

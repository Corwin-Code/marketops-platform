# A bid change whose outcome nobody knows

This is the most serious ordinary state in the advertising write path. A call
went out and nothing usable came back, so the product cannot say whether a real
bid on a real marketplace changed.

## What you will see

A command in `UNKNOWN_REQUIRES_READBACK`, and an attempt whose `outcome_class`
is `UNKNOWN_STATE`.

## The one thing that must not happen

**The call is never repeated.** Not by a worker, not by an operator, not by a
retry. If the first call landed, a second would move the bid twice.

This is enforced in three places and you cannot route around any of them:

- `ops.open_ad_bid_command_attempt` refuses a second `APPLY` for a command that
  has ever had one, whatever its state;
- an `APPLY` cannot be opened from `UNKNOWN_REQUIRES_READBACK` at all;
- the transition graph contains no edge from `UNKNOWN_REQUIRES_READBACK` back to
  `EXECUTING`, so there is no sequence of transitions that reaches a retry.

`AdvertisingTransmissionBoundaryIT#TC-AD-BOUNDARY-006` asserts all three.

## What happens instead

The product observes. A readback is the only route out, and only a readback
that matched the target closes the command successfully:

- `MATCHES_TARGET` — the change landed. The command completes.
- `MATCHES_PRIOR` — the change did not land. The command goes to
  `READBACK_MISMATCH` and compensation is available.
- `DIFFERENT` — the platform holds a third value. Something outside this
  lineage owns that bid now, and the command goes to
  `LATER_CHANGE_OR_MISMATCH_INVESTIGATION`. Do not compensate: restoring "the
  prior bid" would overwrite whatever the third party set.
- `UNREADABLE` — still unknown. It stays unknown.

## When the readback itself will not work

If the provider cannot be read at all, the command stays in
`UNKNOWN_REQUIRES_READBACK` and that is the correct resting place. It counts
against `max_unresolved_transmitted_writes` in the aggregate exposure envelope,
so accumulating them stops new advertising work — which is the intended
pressure, not a bug to work around.

Move it to `MANUAL_RESOLUTION` only when a person has established the truth by
some other means, and record what they looked at:

```sql
SELECT ops.transition_ad_bid_command(:commandId, :fence, :owner, 'MANUAL_RESOLUTION',
        'readback_unavailable', NULL, :correlationId);
```

## What to tell people

An unknown result is not a failure and should not be reported as one. The
honest sentence is: "we asked the marketplace to change a bid, we do not yet
know whether it did, and we will not ask again until we do."

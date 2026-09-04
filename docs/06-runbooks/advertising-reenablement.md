# Restarting advertising after a stop

Reenablement is never time-based and never unilateral. This is the careful half
of `advertising-kill-switch.md` and `advertising-quarantine.md`.

## The five conditions

All of them, recorded one at a time, before anything restarts:

| Condition | What it means |
| --- | --- |
| `ROOT_CAUSE_CLASSIFIED` | somebody can say what happened, not what might have |
| `UNKNOWNS_RESOLVED` | no command is still in an unknown or mismatched state |
| `AUTHORITIES_REPLACED` | any policy version that turned out wrong has a successor |
| `RESULTS_RECONCILED` | what the platform holds matches what the product believes |
| `CAPABILITY_EVIDENCE_CURRENT` | the capability's verification has not lapsed meanwhile |

And, for an `EXECUTION_INTEGRITY`, `PROVIDER_OR_READBACK_DEFECT` or
`CREDENTIAL_OR_SECURITY` cause, a sixth:

| `SECURITY_ATTESTATION_PRESENT` | somebody accountable has attested the technical or security cause is closed |

Record each as it becomes true:

```sql
UPDATE ops.ad_containment SET root_cause_classified = true, ...
```

or through the console. Conditions are recorded separately from lifting, on
purpose: the row-level check that refuses a premature lift needs something
independent to check.

## Two different people

The endorser and the approver must differ from each other **and** from whoever
activated the stop. The table refuses anything else, so one person cannot lift
their own stop by any sequence of calls.

`AdvertisingReservationIT#TC-AD-CONTAIN-002` asserts both refusals: the
activator as both, and one person in both roles.

## Lifting it

```sql
UPDATE ops.ad_containment
   SET state = 'REENABLED', endorsed_by_user_id = :endorser,
       approved_by_user_id = :approver, reenabled_scope = :scopeJson,
       reenabled_at = now()
 WHERE id = :containmentId AND state <> 'REENABLED';
```

`reenabled_scope` is what you are restarting, not what was stopped. Restarting
narrower than you stopped is normal and often right: bring back one store before
the account, one direction before both.

## What to expect afterwards

The write gate still refuses for every other reason it had. Lifting a quarantine
does not verify a capability, publish a policy bundle or authorise a gate. If
you lift a containment and the queue does not move, run the gate and read what
it says:

```sql
SELECT unnest(ops.evaluate_ad_bid_write_gate(:commandId));
```

That is the honest answer, and it is usually a longer list than people expect.

# Resolving a price command a person has to decide

This is the runbook for the alerts `marketops-commands-awaiting-a-person`,
`marketops-readback-mismatch` and `marketops-write-gate-closed`.

## What has happened

A price command has stopped moving on its own. The platform will not resolve it
automatically, and it is right not to: every remaining path involves a judgement
about a real marketplace price.

The following durable states require operator attention.

| State | What is true | What is not known |
| --- | --- | --- |
| `UNKNOWN_REQUIRES_READBACK` | A call was made and its outcome could not be classified — a timeout, a server failure, or an answer this product cannot read. | Whether the marketplace applied the change. |
| `READBACK_MISMATCH` | A readback succeeded and observed a price other than the intended one. | Whether this product wrote the wrong value or something else moved the price afterwards. |
| `COMPENSATION_FAILED` | A restore was not confirmed. | Whether the prior price was restored; do not retry blindly. |
| `MANUAL_RESOLUTION` | A person has already taken the command over. | Whatever they had not yet decided. |

The one thing that is never true here is that the change succeeded. Success
requires a readback that observed the intended value, and the database refuses
the transition without one.

Real provider reads/writes and production incident actions need their separately
approved execution envelope. This runbook does not authorize them during rework.

## Before anything else

**Do not repeat the write.** There is no transition from
`UNKNOWN_REQUIRES_READBACK` back to executing, and the reason is that a repeat
would change the same price twice with nobody able to say how many times it
moved. If you find yourself wanting to re-submit the recommendation, stop and
read the command timeline instead.

## Step 1 — read what actually happened

Open the command in the console:

```
GET /api/v1/console/commands/{commandId}
```

The timeline shows every call made and every observation taken. Read the last
attempt's `outcomeClass` and `errorCode`, then read the readbacks. A command
with no readback at all has no recorded comparison against the marketplace.
After a new login, the recommendation review can open its existing command
through `GET /api/v1/console/commands/recommendations/{recommendationId}`;
this is an ownership-checked read and creates no command.

## Step 2 — ask the marketplace what it holds

For a command in `UNKNOWN_REQUIRES_READBACK`, this is the whole resolution:

```
POST /api/v1/console/commands/{commandId}/readback
{ "reason": "resolving an unclassified apply outcome" }
```

The action needs the `COMMAND_RESOLVE` grant and a recent sign-in. It performs a
read, never a write. Three outcomes follow.

- The readback observes the **intended** price. The command completes as
  succeeded, the change was applied, and nothing further is needed.
- The readback observes a **different** price. The command moves to
  `READBACK_MISMATCH`; continue at step 3.
- The readback cannot be read at all. The command returns to
  `UNKNOWN_REQUIRES_READBACK` with an `UNREADABLE` observation recorded. Wait
  for the marketplace to recover and repeat; if it does not recover within the
  hour, take the command over (step 4).

## Step 3 — decide about a mismatch

The readback observed something unintended. Look at which:

- **The previous price.** The write did not take effect. Close the command as
  failed (step 5) and let the recommendation be rebuilt from current facts.
- **A third value.** Something outside this product moved the price — a
  marketplace promotion, another tool, or a person. Restoring is not correct
  here: the platform would overwrite a change somebody made. Take the command
  over (step 4) and resolve it with whoever owns that change.

If the intended value was applied and then changed again by something else, the
latest readback will no longer match the target, and the platform will refuse a
restore. That refusal is the guarantee, not an obstacle.

## Step 4 — take a command over

```
POST /api/v1/console/commands/{commandId}/manual-resolution
{ "reason": "why this needs a person" }
```

This records that you own it. It changes nothing on the marketplace.

## Step 5 — close a command that will not complete

```
POST /api/v1/console/commands/{commandId}/closure
{ "reason": "why this will not be completed" }
```

The command ends as failed. Nothing is restored. Closure records an operator
decision; it is not independent evidence that no remote change occurred.
Keep the last observation and any unresolved uncertainty in the incident record.

## Step 6 — restore the previous price

Only when this product's change is still what the marketplace holds:

```
POST /api/v1/console/commands/{commandId}/compensation
{ "reason": "why the previous price should be put back" }
```

The platform takes a fresh preflight readback and requires both the command
target and a usable provider version token. The restore carries that token as
a conditional write, so a concurrent change must be refused by the provider.
A capability without those verified semantics cannot restore safely. If it refuses with `COMPENSATION_UNSAFE`, take a fresh
readback first and re-read step 3: the world has moved since.

A restore is a platform write. It passes the same gate an apply does, and it is
not complete until a readback has observed the previous value.

## When the gate is closed

The alert `marketops-write-gate-closed` fires when approved commands are being
refused before any call is made. Ask why:

```
GET /api/v1/console/commands/{commandId}/gate
```

Every blocking condition is listed. The common ones and what they mean:

| Reason | What to do |
| --- | --- |
| `GLOBAL_SWITCH_DISABLED` / `CAPABILITY_SWITCH_DISABLED` / `SCOPED_SWITCH_DISABLED` | A kill switch is off. Find out who threw it and why before re-enabling — see `kill-switch.md`. |
| `ENTITY_NOT_ALLOWLISTED` | The listing is not in the pilot cohort, or its entry expired. Widening the cohort is an Owner decision. |
| `AUTHORIZATION_INVALID_OR_EXPIRED` | The approval has lapsed, or the subject's facts moved since it was given. The recommendation must be reviewed again. |
| `RECOMMENDATION_STALE` | The proposal's validity window elapsed. Rebuild it from current facts. |
| `MAPPING_UNRESOLVED` / `MAPPING_CONFLICT_OPEN` | The listing does not resolve to one internal variant. Resolve the mapping first; the profit case is about a different product until you do. |
| `GUARDRAIL_NOT_PASSED` | The deterministic guardrail has not passed for execution. Take an impact preview and read the reasons. |
| `CAPABILITY_NOT_VERIFIED` | The marketplace capability's evidence has lapsed. It must be re-verified before any write. |

## What to record

Every action above already records itself: the reason you typed, who you are and
when you authenticated all land in the audit journal beside the transition. Add
nothing further to a ticket that is not in that journal — the journal is what a
later review reads.

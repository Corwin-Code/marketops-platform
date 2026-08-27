# Stopping and restarting platform writes

This is the runbook for turning marketplace writes off, and for the much more
careful business of turning them back on.

## When to throw it

Immediately, without waiting for certainty, when any of these is true:

- prices are moving on a marketplace in a way nobody recognises;
- more than one command has landed in `READBACK_MISMATCH` in a short period;
- a marketplace is answering writes with something the platform cannot classify;
- a cost, fee or currency figure has been found to be wrong, and price
  recommendations were built on it;
- anybody with the grant believes something is wrong and cannot yet say what.

The last one is deliberate. The cost of stopping is a delay; the cost of not
stopping is real money on somebody's storefront. Throw it and investigate.

## Throwing it

Off is never gated beyond holding the `KILL_SWITCH_OPERATE` grant — no step-up
prompt, no second approval. A delay measured in seconds is a delay measured in
price changes.

Global — stops every write everywhere:

```
POST /api/v1/console/commands/kill-switch/disable
{ "scopeKind": "GLOBAL", "reason": "unrecognised price movement on Ozon" }
```

Narrower scopes, when you know where the problem is:

```
POST /api/v1/console/commands/kill-switch/disable
{ "scopeKind": "STORE", "scopeReference": "<store id>", "storeId": "<store id>",
  "reason": "cost data for this store is under review" }
```

`scopeKind` accepts `GLOBAL`, `PLATFORM`, `MARKETPLACE_ACCOUNT`, `STORE` and
`CAPABILITY`. A disabled flag at any scope blocks; the switches are a
conjunction, so a narrower one does not reopen a wider one that is off.

## What it does and does not do

It stops **new** writes. Every command is gated when a worker claims it, so a
switch thrown while a worker is deciding is seen by the gate.

It does **not** reach into a command that has already been claimed. A call in
flight completes, and its readback follows. The number of commands still moving
is recorded on the switch event, and the response tells you:

```
GET /api/v1/console/commands/kill-switch/history
```

Read `inFlightCommandCount` on the event you just created. If it is above zero,
those commands will finish and appear on the store's needing-attention list.
Watch that list rather than assuming everything stopped.

## Turning it back on

Re-enabling widens real commercial exposure, so it needs a recent sign-in as
well as the grant:

```
POST /api/v1/console/commands/kill-switch/enable
{ "scopeKind": "GLOBAL", "reason": "cost correction applied and verified" }
```

Before you do, four things must be true, and each must be checkable by somebody
other than you:

1. **The cause is understood.** Not "it stopped happening" — the specific
   condition, named.
2. **The condition is corrected.** If a cost was wrong, the corrected cost is in
   the platform and the affected metrics have been recomputed.
3. **Every command that was in flight is resolved.** The store's
   needing-attention list is empty, or every entry on it has a recorded
   decision. See `price-command-resolution.md`.
4. **Somebody else agrees.** The grant is enough for the platform; a second
   pair of eyes is what stops the person who caused the incident from ending it.

## Verifying it took effect

Read the switch state directly:

```
GET /api/v1/console/commands/kill-switch
```

Then confirm the gate agrees, on a real command:

```
GET /api/v1/console/commands/{commandId}/gate
```

A disabled global switch shows as `GLOBAL_SWITCH_DISABLED` in the blocking
reasons. If it does not, the switch did not take effect and you are not
protected — treat that as an incident of its own.

## What is recorded

Every movement records who, when, why, and how many commands were still moving.
A kill and a re-enable are equally attributable, which is the point: the
question after an incident is usually not who stopped it but who started it
again.

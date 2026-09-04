# Stopping every advertising write

The advertising kill switch is the instrument for when you do not know where the
problem is. Use it early; the narrower ones are in
`advertising-quarantine.md`.

## When to throw it

Immediately, without waiting for certainty, when any of these is true:

- bids are moving on a marketplace in a way nobody recognises;
- more than one command has landed in `READBACK_MISMATCH` in a short period;
- a marketplace is answering advertising writes with something the product
  cannot classify;
- spend is rising against advertising the product recently changed;
- anybody with the grant believes something is wrong and cannot yet say what.

The last one is deliberate. The cost of stopping is a delay. The cost of not
stopping is somebody's advertising budget, spent on something nobody chose.

## Throwing it

The advertising capability shares the write registry's switches with the price
path, so the global switch stops both. A narrower advertising-only stop is a
`KILL_SWITCH_ACTIVE` containment at the capability scope:

```sql
INSERT INTO ops.ad_containment (id, organization_id, containment_kind, scope_kind,
        platform_code, store_id, capability_code, cause_class, reason,
        evidence_reference, activated_by_user_id, activated_at, state,
        accountable_role_code, correlation_id, created_at, updated_at)
VALUES (gen_random_uuid(), :organizationId, 'KILL_SWITCH_ACTIVE',
        'PLATFORM_STORE_CAPABILITY', :platformCode, :storeId, 'ad-bid-change',
        'BUSINESS_HARM', :reason, :evidenceReference, :userId, now(), 'ACTIVE',
        'OPS_LEAD', :correlationId, now(), now());
```

No step-up, no second approval, no waiting period. A delay measured in seconds
is a delay measured in bid changes.

## Confirming it took

```sql
SELECT ops.ad_active_containment(:organizationId, :objectId, :storeId,
        :platformCode, 'ad-bid-change', :affectedSetDigest);
```

An empty array means nothing is held. It does **not** mean anything is
permitted — the write gate asks several other questions, and this is one of
them.

To see the whole picture for a command:

```sql
SELECT unnest(ops.evaluate_ad_bid_write_gate(:commandId));
```

That returns every reason rather than the first, so you can see the whole
distance to a usable configuration rather than fixing one thing at a time.

## What it does not do

It does not undo anything. A bid already changed stays changed; restoring it is
compensation, which needs a current readback proving this command still owns the
value. See `advertising-outcome-regression.md`.

It does not stop the calculation. Cases keep being computed and the queue keeps
being ranked, which is what you want — you need to see what is happening while
nothing is being sent.

## Getting out

Never by letting time pass. See `advertising-reenablement.md`.

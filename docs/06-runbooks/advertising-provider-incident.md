# A marketplace that is not answering, or answering strangely

## What counts as an incident here

- advertising reads failing or returning shapes the adapter cannot classify;
- reported spend that moves in a way nobody recognises;
- a provider restating figures well outside its own correction window;
- any answer to a write this product cannot classify.

The last one is the serious one, and it has its own runbook:
`advertising-unknown-result.md`.

## The first decision

Ask one question: **could a real bid have changed?**

If the answer is no — reads only — you have an availability problem, not a
safety problem. Follow `acquisition-backlog.md` and let the freshness profiles
do their work. Cases will block themselves as their evidence ages, which is the
behaviour you want.

If the answer is yes, or you cannot tell, throw the kill switch first and
investigate second. See `advertising-kill-switch.md`. The cost of stopping is a
delay; the cost of not stopping is somebody's advertising budget.

## Containing it at the right scope

Containment is scoped, and the scope should match what you actually know:

| What you know | Scope |
| --- | --- |
| one advertising object is behaving oddly | `ENTITY` |
| a set of promoted products is affected | `AFFECTED_SET` |
| one store's advertising on one platform | `PLATFORM_STORE_CAPABILITY` |
| the whole account | `PLATFORM_ACCOUNT_CAPABILITY` |
| you do not yet know | kill switch, global |

```sql
INSERT INTO ops.ad_containment (id, organization_id, containment_kind, scope_kind,
        platform_code, store_id, capability_code, cause_class, reason,
        evidence_reference, activated_by_user_id, activated_at, state,
        accountable_role_code, correlation_id, created_at, updated_at)
VALUES (gen_random_uuid(), :organizationId, 'CAPABILITY_QUARANTINED',
        'PLATFORM_STORE_CAPABILITY', :platformCode, :storeId, 'ad-bid-change',
        'PROVIDER_OR_READBACK_DEFECT', :reason, :evidenceReference, :userId, now(),
        'ACTIVE', 'TECH_DATA', :correlationId, now(), now());
```

A `PROVIDER_OR_READBACK_DEFECT` cause additionally requires a security or
platform attestation before anything restarts. That is deliberate: a provider
whose answers could not be classified is a provider whose behaviour has to be
re-established, not merely waited out.

## Getting out again

Follow `advertising-reenablement.md`. It is not a matter of time passing.

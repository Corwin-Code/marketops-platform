# An advertising object whose affected set will not resolve

One advertising object promotes several product variants, and a bid change
reaches every one of them. The set of variants it reaches is the **affected
set**, and until it resolves completely nothing about that object can be acted
on.

## What you will see

A case blocked with `AFFECTED_SET_UNRESOLVED` or `AFFECTED_SET_NEVER_RESOLVED`,
or a proposal that never appears for an object you expected one for.

## Why it blocks rather than proceeding partially

A partially resolved affected set means the product knows some of the variants
this bid touches and not others. Acting on it would be reserving against
variants that were never listed — so the reservation would claim to hold
products it does not hold, and a second intervention could act on the ones it
missed. Both interventions would then have outcomes nobody could attribute.

The refusal is structural: `AdvertisingCandidateRepository.resolvedAffectedSet`
requires `resolution_state = 'COMPLETE'`, and `ops.take_ad_action_reservation`
is the only route to a reservation.

## Finding the gap

```sql
SELECT a.id, a.resolution_state, a.unresolved_reason_codes,
       cardinality(a.product_variant_ids) AS variants,
       cardinality(a.platform_listing_variant_ids) AS listings, a.resolved_at
  FROM core.ad_affected_set a
 WHERE a.ad_native_object_id = :objectId
 ORDER BY a.resolved_at DESC LIMIT 5;
```

The reason codes name what stopped it. The common ones:

- a promoted listing variant has no confirmed internal mapping — resolve it
  through the mapping journey, not here;
- a mapping conflict is open for one of the listings — see
  `MAPPING_CONFLICT_OPEN`, and note the write gate refuses independently of the
  affected set for that reason;
- the object's structural relationships were observed incompletely, so the
  product cannot enumerate what it promotes.

## What to do

1. Resolve the listing-to-SKU mappings. The advertising queue will pick the
   object up on the next targeted pass; you do not need to do anything here.
2. If a mapping conflict is open, resolve the conflict. A conflicted mapping
   means the product does not know whose sales this bid affects, and that is a
   refusal in its own right.
3. If the object's structure is the problem — a campaign whose keywords were not
   fully observed — this is a read problem. Follow
   `advertising-stale-or-incomplete-data.md`.

## What must not happen

Do not create an affected set by hand, and do not mark one COMPLETE that is not.
The digest of the set is bound into the reservation, the candidate and the
command; a set that says COMPLETE and is not would make every one of those
agree with each other about something untrue.

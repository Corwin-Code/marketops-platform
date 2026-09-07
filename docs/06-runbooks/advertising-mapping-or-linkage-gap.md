# An advertising object whose affected set or sales linkage will not resolve

An advertising intervention reaches every product variant in its native affected
set. Partial membership blocks candidates and reservations. Responsibility and
DataRepair Tasks continue to identify the missing work and any independently
proven Protection harm.

## Identify the missing dependency

```sql
SELECT a.id,a.resolution_state,a.unresolved_reason_codes,a.affected_set_digest,
       cardinality(a.product_variant_ids) AS variants,
       cardinality(a.platform_listing_variant_ids) AS listings,a.resolved_at
  FROM core.ad_affected_set a
 WHERE a.organization_id=:organizationId AND a.ad_native_object_id=:objectId
 ORDER BY a.resolved_at DESC,a.id LIMIT 5;
```

Distinguish native structural membership from sales attribution. A complete set
may still contain a sale line whose platform listing, effective internal product
mapping, conversion stage or amount is missing. That line remains in the observed
denominator and attribution-gap evidence. It is not dropped to make the remaining
lines look complete, and another variant's cost or CPA does not fill its gap.
Canonical economics apply each line's actual net amount, quantity, listing,
product and effective cost/fee authority. Retained quantities already exclude
returns; return loss is not charged a second time.

## Repair the authority that is missing

1. Resolve listing-to-product mappings through the governed mapping journey.
   Preserve effective intervals; historical sale linkage is evaluated as of its
   event and the calculation's acceptance cutoff.
2. Resolve open mapping conflicts. A partial or conflicted set cannot be reserved
   by manually listing the subset currently understood.
3. Repair incomplete native object structure through acquisition. Check the
   official read's scope and pagination rather than constructing membership by
   hand. Follow `advertising-stale-or-incomplete-data.md` for missing windows.
4. Check the refreshed case, exact affected-set digest and distinct DataRepair
   responsibility. A newer mapping or membership snapshot invalidates old action
   authority; an old candidate does not automatically inherit it.

The targeted refresh must include both old and new memberships of a mapping
change. Company sales guards aggregate all company channels for each affected
ProductVariant, so a sales or settlement change on another store/platform can
also affect an advertising object promoting that same product. The guard is not
limited to the advertised listing and does not include unrelated products.

## Why partial execution is refused

The complete set and digest bind the candidate, selected baseline, reservation,
review chain and command or manual packet. Reservations exclude overlapping
ProductVariants across objects and channels. A hand-built subset would leave
unreserved products exposed to another intervention and make the outcome
unattributable. Do not create an affected set by hand or mark it complete to
bypass this boundary. Raw lineage and official facts remain unchanged while the
mapping authority is repaired.

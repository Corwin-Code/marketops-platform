package com.mimococo.marketops.productlisting;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Published resolution from platform listing identity to internal identity.
 *
 * <p>This is the contract every other module asks before it attaches cost,
 * profit or a platform write to a listing. It answers only what is confirmed:
 * an unmapped listing variant resolves to nothing, and a listing variant whose
 * mapping is disputed reports its conflict rather than a best guess.
 *
 * <p>Resolution is time-aware because a mapping is effective-dated. A profit
 * figure computed for last month must resolve the mapping that was in force
 * then, not the one somebody corrected yesterday.
 */
public interface ListingIdentityDirectory {

    /** The internal variant a listing variant resolved to at an instant, if any. */
    Optional<UUID> internalVariantAt(UUID platformListingVariantId, Instant at);

    /** Resolve many listing variants at once, omitting the unresolved ones. */
    Map<UUID, UUID> internalVariantsAt(Collection<UUID> platformListingVariantIds, Instant at);

    /** Whether an unresolved mapping conflict currently blocks this listing variant. */
    boolean hasOpenConflict(UUID platformListingVariantId);

    /**
     * Where one listing variant sits and what it maps to at an instant.
     *
     * <p>Returned as one value because consumers need the store, the platform
     * and the internal variant together; separate lookups could disagree about
     * the same instant, and a guardrail built on such a disagreement would be
     * checking one listing's price against another listing's cost.
     */
    Optional<ListingVariantContext> variantContext(UUID platformListingVariantId, Instant at);
}

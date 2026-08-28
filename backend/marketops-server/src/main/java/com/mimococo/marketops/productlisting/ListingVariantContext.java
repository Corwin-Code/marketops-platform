package com.mimococo.marketops.productlisting;

import java.util.UUID;

/**
 * Where one platform listing variant sits, and what it currently maps to.
 *
 * <p>Consumers need this together rather than in pieces: a guardrail checking a
 * price needs the store, the platform and the internal variant at once, and
 * three separate lookups would let them disagree about the same instant.
 *
 * @param listingVariantId the platform listing variant
 * @param listingId the listing it belongs to
 * @param storeId store the listing sits on
 * @param marketplaceAccountId account the store belongs to
 * @param platformCode marketplace the listing lives on
 * @param nativeListingKey the marketplace's own listing identifier
 * @param nativeVariantKey the marketplace's own variant identifier
 * @param productVariantId the internal variant it maps to, or {@code null}
 * @param conflictOpen whether an unresolved mapping conflict blocks it
 */
public record ListingVariantContext(
        UUID listingVariantId,
        UUID listingId,
        UUID storeId,
        UUID marketplaceAccountId,
        String platformCode,
        String nativeListingKey,
        String nativeVariantKey,
        UUID productVariantId,
        boolean conflictOpen) {

    /** Whether the variant currently resolves to an internal variant. */
    public boolean mapped() {
        return productVariantId != null;
    }

    /** Whether anything currently blocks attaching cost or a write to it. */
    public boolean blocked() {
        return !mapped() || conflictOpen;
    }
}

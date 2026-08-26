package com.mimococo.marketops.productlisting;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One listing as a source reported it, in platform-neutral terms.
 *
 * <p>Every field the marketplace owns is carried verbatim. Nothing here is
 * normalised toward internal identity, because the whole point of the mapping
 * step is that the two identities stay separable afterwards.
 *
 * @param storeId store the listing was observed on
 * @param nativeListingKey the marketplace's own listing identifier
 * @param nativeProductKey the marketplace's product identifier, or {@code null}
 * @param title published title, or {@code null}
 * @param nativeStatus the marketplace's own status word, or {@code null}
 * @param variants the listing's variants as reported
 */
public record ObservedListing(
        UUID storeId,
        String nativeListingKey,
        String nativeProductKey,
        String title,
        String nativeStatus,
        List<ObservedListingVariant> variants) {

    public ObservedListing {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(nativeListingKey, "nativeListingKey");
        variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
    }
}

package com.mimococo.marketops.productlisting;

/**
 * What an operator calls a whole platform listing.
 *
 * <p>Presentation only, like {@link SubjectIdentity}: it decides nothing and
 * nothing may be keyed on it. The catalogue side comes from the mappings in
 * force now; {@code productName} is present only when every mapped variant of
 * the listing agrees on one product, so a listing that spans products is never
 * shown under one product's name.
 *
 * @param platformCode marketplace the listing lives on
 * @param nativeListingKey the marketplace's listing key
 * @param nativeProductKey the marketplace's product key, or {@code null}
 * @param title the marketplace title (marketplace-language data), or {@code null}
 * @param productName our product name when exactly one product is mapped, else {@code null}
 * @param productCount how many distinct products the listing's variants map to; 0 = unmapped
 */
public record ListingIdentity(
        String platformCode,
        String nativeListingKey,
        String nativeProductKey,
        String title,
        String productName,
        int productCount) {
}

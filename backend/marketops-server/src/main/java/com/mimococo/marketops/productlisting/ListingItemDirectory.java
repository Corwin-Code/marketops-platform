package com.mimococo.marketops.productlisting;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolving the marketplace item identifier some datasets name a variant by.
 *
 * <p>A marketplace can key its catalog by one identifier and its analytics or
 * finance answers by another (Ozon: product id and SKU). The catalog states both,
 * and this module records them; everything else resolves the item identifier
 * through what was recorded rather than guessing a listing from it.
 */
public interface ListingItemDirectory {

    /**
     * The marketplace's own listing and variant keys the store recorded under an
     * item identifier, when exactly one observed variant carries it.
     */
    Optional<ListingKeys> byItemKey(UUID storeId, String nativeItemKey);

    /**
     * The keys a listing variant is recorded under.
     *
     * @param nativeListingKey the marketplace identifier of the listing
     * @param nativeVariantKey the marketplace identifier of the variant
     */
    record ListingKeys(String nativeListingKey, String nativeVariantKey) {
    }
}

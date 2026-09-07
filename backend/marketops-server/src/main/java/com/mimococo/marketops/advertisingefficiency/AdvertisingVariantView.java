package com.mimococo.marketops.advertisingefficiency;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One internal variant inside a case's affected set, as it is diagnosed.
 *
 * <p>{@code basis} is the field that matters. A number allocated to this variant
 * rather than observed at it can support diagnosis and nothing else, and a
 * console that rendered the two identically would let an operator believe a
 * per-SKU profit figure the marketplace never reported.
 */
public record AdvertisingVariantView(
        UUID productVariantId,
        UUID platformListingVariantId,
        String skuCode,
        String displayName,
        String basis,
        String confidenceState,
        BigDecimal spendAmount,
        Long clicks,
        BigDecimal contributionProfitAmount,
        String currencyCode,
        String sellabilityState,
        String availabilityState,
        boolean criticalSalesUnit) {
}

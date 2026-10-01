package com.mimococo.marketops.analyticsdecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What one unit of a listing would earn at a price it does not have yet, by the store's official
 * arithmetic ({@link ListingUnitEconomics}): today's stated tariffs, the store's fulfilment scheme
 * and the mapped unit cost. A price suggestion states the margin it keeps with this.
 */
public interface ListingPriceEstimateQuery {

    /**
     * The estimate at {@code price}, or empty when the tariffs, the scheme or the unit cost are
     * missing, or the cost is in a currency other than {@code currencyCode}.
     */
    Optional<ListingUnitEconomics.Estimate> estimateAt(UUID storeId, UUID listingVariantId, BigDecimal price,
                                                       String currencyCode, Instant asOf);
}

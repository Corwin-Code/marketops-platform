package com.mimococo.marketops.marketplaceintegration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What this product has already done to a listing variant's price.
 *
 * <p>The cooldown and the cumulative daily bound are about changes this system
 * made, not about every movement the marketplace shows. A platform promotion
 * that moved the observed price is not a change to wait out, and treating it as
 * one would freeze a listing for reasons nobody chose.
 *
 * <p>Only commands that actually reached the platform count. A command that was
 * refused, or that failed before any attempt, changed nothing and must not
 * consume the day's allowance.
 */
public interface PriceChangeHistory {

    /**
     * The total proportional movement applied since an instant, as a magnitude.
     *
     * <p>Rises and falls both count towards the same allowance, because the
     * bound exists to limit disruption rather than to limit direction.
     */
    BigDecimal cumulativeChangeRate(UUID platformListingVariantId, Instant since);

    /** When the price was last changed by this product, if it ever was. */
    Optional<Instant> lastChangeAt(UUID platformListingVariantId);
}

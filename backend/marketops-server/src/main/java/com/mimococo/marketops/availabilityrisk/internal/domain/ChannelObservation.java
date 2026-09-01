package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What one exact platform listing and fulfillment mode currently shows.
 *
 * <p>This is deliberately narrow. A channel answer is a statement about one
 * observed thing, and widening the input would let an unrelated defect
 * elsewhere change a conclusion that the Contract requires to stay independently
 * actionable.
 *
 * @param platformListingVariantId the exact listing variant
 * @param storeId the store it belongs to
 * @param fulfillmentModeCode the exact mode
 * @param availableUnits units the source reported, or {@code null} when it reported none
 * @param observedAt when the source considered it true, or {@code null} when unknown
 * @param sellability whether it could be bought
 * @param blockedReason why it could not, or {@code null}
 * @param provenanceId the fact this came from, or {@code null}
 */
public record ChannelObservation(
        UUID platformListingVariantId,
        UUID storeId,
        String fulfillmentModeCode,
        Integer availableUnits,
        Instant observedAt,
        Sellability sellability,
        String blockedReason,
        UUID provenanceId) {

    public ChannelObservation {
        Objects.requireNonNull(platformListingVariantId, "platformListingVariantId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(fulfillmentModeCode, "fulfillmentModeCode");
        Objects.requireNonNull(sellability, "sellability");
        if (availableUnits != null && availableUnits < 0) {
            throw new IllegalArgumentException("availableUnits cannot be negative");
        }
    }

    /**
     * Whether the observation is recent enough to describe the present.
     *
     * <p>An observation with no source time is never fresh. The source declined
     * to say when it was true, and an unknown age cannot be inside a bound.
     */
    public boolean freshAt(Instant asOf, long freshnessMaxMinutes) {
        return observedAt != null
                && !observedAt.plusSeconds(freshnessMaxMinutes * 60L).isBefore(asOf);
    }
}

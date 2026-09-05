package com.mimococo.marketops.marketplaceintegration;

import java.util.Objects;
import java.util.UUID;

/** Identifies an already sealed decision; actor, Bundle and expiry are derived in the database. */
public record AdBidCommandRequest(UUID recommendationId, long expectedVersion, UUID reservationId) {
    public AdBidCommandRequest {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(reservationId, "reservationId");
    }
}

package com.mimococo.marketops.marketplaceintegration;

import java.util.Objects;
import java.util.UUID;

/** Identifies an approved proposal; all executable values are derived by the database. */
public record PriceCommandRequest(UUID recommendationId, long expectedVersion, UUID actorId) {
    public PriceCommandRequest {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(actorId, "actorId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be nonnegative");
        }
    }
}

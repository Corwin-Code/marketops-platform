package com.mimococo.marketops.marketplaceintegration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The workflow asking the execution boundary to create one bid command.
 *
 * <p>Everything here was decided before the request was made: the candidate is
 * already generated and provider-normalized, the approval is already given, the
 * reservation is already held. The execution boundary's job is to refuse if any
 * of that has stopped being true, not to make any of it true.
 */
public record AdBidCommandRequest(
        UUID recommendationId,
        long expectedVersion,
        UUID actorUserId,
        UUID reservationId,
        UUID bundleId,
        Instant approvalExpiresAt) {

    public AdBidCommandRequest {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(bundleId, "bundleId");
        Objects.requireNonNull(approvalExpiresAt, "approvalExpiresAt");
    }
}

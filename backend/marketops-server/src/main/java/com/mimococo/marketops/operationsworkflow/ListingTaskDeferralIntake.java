package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Finite reconsideration of the same work; deferral never pauses its responsibility clocks. */
public interface ListingTaskDeferralIntake {
    View request(AuthenticatedActor actor, UUID listingId, UUID taskId, int minutes, String reason);
    Optional<View> current(UUID taskId);
    Optional<View> at(UUID taskId, Instant asOf);
    /** Caller queues these reviews in this same transaction. */
    List<ReviewRequest> expireDue(int limit);
    void queued(UUID deferralId, UUID queueId);
    void reassessed(UUID healthId);

    record View(UUID id, int minutes, String reason, Instant requestedAt, Instant expiresAt,
                String state, UUID reviewHealthId) { }
    record ReviewRequest(UUID id, UUID organizationId, UUID listingId, boolean necessaryRisk, Instant expiredAt) { }
}

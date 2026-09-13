package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Finite evidence-bound dependency pause of an existing Listing Task action stage. */
public interface ListingTaskDependencyHold {
    View request(AuthenticatedActor actor, UUID listingId, UUID taskId, UUID dependencyTaskId,
                 int minutes, String evidenceReference);
    Optional<View> current(UUID taskId);
    int synchronizeDue(int limit);

    record View(UUID id, UUID dependencyTaskId, int minutes, String evidenceReference,
                Instant startedAt, Instant expiresAt, String state, Instant endedAt,
                String endReason) { }
}

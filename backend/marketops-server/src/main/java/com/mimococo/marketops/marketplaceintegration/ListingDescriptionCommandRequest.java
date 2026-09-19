package com.mimococo.marketops.marketplaceintegration;

import java.util.Objects;
import java.util.UUID;

/**
 * What the workflow hands the execution boundary to create a description command.
 *
 * @param actionId the launched listing action
 * @param expectedVersion the action version the caller read
 * @param actorUserId the person creating the command
 */
public record ListingDescriptionCommandRequest(UUID actionId, long expectedVersion, UUID actorUserId) {
    public ListingDescriptionCommandRequest {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(actorUserId, "actorUserId");
    }
}

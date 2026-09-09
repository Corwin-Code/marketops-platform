package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.UUID;

/** A change observed after adoption, with the association it was given. */
public record LateAssociationView(UUID id, UUID platformListingId, UUID actionId, String associationKind,
                                  UUID observationId, Instant operationTime, Instant reportTime,
                                  String authorityGap, String forwardDisposition, String state,
                                  UUID closureVerificationId, UUID recordedByUserId, Instant recordedAt) {
}

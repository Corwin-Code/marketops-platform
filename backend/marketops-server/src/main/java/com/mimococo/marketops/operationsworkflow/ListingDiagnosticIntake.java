package com.mimococo.marketops.operationsworkflow;

import java.util.UUID;

/** A retained Listing diagnosis activates existing Workflow responsibility, never a platform action. */
public interface ListingDiagnosticIntake {
    void synchronize(UUID healthId, ListingResponsibilityBasis basis);
    void acknowledge(com.mimococo.marketops.identityaccess.AuthenticatedActor actor, UUID listingId, UUID taskId);
}

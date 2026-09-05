package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import java.util.UUID;

/** The shared disclosure boundary for console, workflow and derived delivery projections. */
public interface AdvertisingDisclosurePolicy {
    tools.jackson.databind.node.ObjectNode discloseRecommendation(AuthenticatedActor actor, RecommendationView view);

    tools.jackson.databind.node.ArrayNode discloseTaskEvents(AuthenticatedActor actor, UUID objectId,
            String affectedSetDigest, java.util.List<WorkTaskEventView> events);

    boolean mayReadNativeRecommendation(AuthenticatedActor actor, UUID recommendationId);

    boolean mayReadNativeCommand(AuthenticatedActor actor, UUID commandId);

    boolean mayReadDecisionEvidence(AuthenticatedActor actor, UUID adNativeObjectId);

    boolean mayReadDecisionEvidence(AuthenticatedActor actor, UUID adNativeObjectId, String affectedSetDigest);

    void requireDecisionEvidence(AuthenticatedActor actor, UUID adNativeObjectId);

    void requireDecisionEvidence(AuthenticatedActor actor, UUID adNativeObjectId, String affectedSetDigest);
}

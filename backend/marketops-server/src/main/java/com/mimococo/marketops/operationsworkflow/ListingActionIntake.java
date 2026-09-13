package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * How the listing conversion module reaches the workflow's authority.
 *
 * <p>Recommendation, Task, journal and state remain the workflow's alone. The
 * listing module proposes, asks for a responsibility Task, records the
 * structured actions it performed and moves the proposal along the reviewed
 * recommendation states; it never writes those tables itself.
 */
public interface ListingActionIntake {

    /** Propose a listing action as a draft that a person must review. */
    UUID proposeListingAction(ListingActionProposal proposal);

    /** The proposal was attested by a reviewer other than its author and may now be decided. */
    void markReadyForReview(String operator, UUID recommendationId, long expectedVersion);

    /** A review returned the proposal to its author; it stays a draft. */
    void withdraw(String operator, UUID recommendationId, String reason, long expectedVersion);

    /** The one responsibility Task of a listing, raised once and reused. */
    UUID ensureResponsibilityTask(UUID organizationId, UUID recommendationId, String title,
                                  Instant dueAt, Instant raisedAt);

    /** Raise ordinary Listing work under its exact accepted clock and coverage values. */
    UUID ensureGovernedResponsibilityTask(UUID organizationId, UUID recommendationId, String title,
                                         ListingResponsibilityBasis basis, Instant raisedAt);

    /** The responsibility Task of a proposal, when one exists. */
    Optional<UUID> taskForRecommendation(UUID recommendationId);

    /** Explicit human acknowledgement through the existing Task authorization and journal. */
    void acknowledgeResponsibility(AuthenticatedActor actor, UUID recommendationId);

    /** A structured action a person performed against the Task, with its evidence. */
    void recordTaskAction(AuthenticatedActor actor, UUID recommendationId, String actionKind,
                          String evidenceReference, String reason);

    /** What an action turned out to have achieved, observed later against evidence. */
    void recordTaskOutcome(UUID recommendationId, String outcomeKind, String outcomeReference,
                           String reason);
}

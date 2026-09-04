package com.mimococo.marketops.operationsworkflow;

import java.util.UUID;

/**
 * How a calculated advertising case becomes somebody's decision.
 *
 * <p>The same shape and the same reason as {@link AvailabilityCaseIntake}: the
 * workflow owns recommendations and tasks, the advertising module owns what a
 * bid case is, and the dependency runs one way. Advertising knows about the
 * workflow; the workflow never has to know what a Max CPC is.
 *
 * <p>Proposing does not approve anything and reaches no marketplace. It creates
 * the proposal a person will decide, with the task and the service level that
 * belong to it.
 */
public interface AdvertisingRecommendationIntake {

    /**
     * Propose one bid change, or return the live proposal that already exists.
     *
     * <p>Idempotent on the object and the action. One advertising object has at
     * most one live bid-change proposal, because two would be two people
     * deciding the same bid.
     */
    UUID proposeBidChange(AdvertisingBidProposal proposal);
}

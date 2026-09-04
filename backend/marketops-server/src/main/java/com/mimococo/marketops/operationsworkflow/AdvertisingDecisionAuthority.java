package com.mimococo.marketops.operationsworkflow;

import java.util.Optional;
import java.util.UUID;

/**
 * What the advertising module can tell the workflow about an approved decision.
 *
 * <p>The workflow owns approval and execution and knows nothing about bids;
 * the advertising module knows what a bid decision consists of and nothing
 * about approving one. So the workflow declares what it needs here and the
 * advertising module supplies it, the same way availability supplies cases
 * through {@link AvailabilityCaseIntake}.
 *
 * <p>The direction matters. An interface owned by the advertising module would
 * make the workflow depend on it, and the workflow is what everything else
 * already depends on.
 *
 * <p>Answering does not authorize anything. The database re-checks every element
 * of the scope inside the transaction that creates the command, and the write
 * gate checks them again at lease and at transmission. This exists so a refusal
 * can be explained before an operator presses the button, not so a check can be
 * skipped afterwards.
 */
public interface AdvertisingDecisionAuthority {

    /**
     * The complete decision scope for one approved bid-change recommendation.
     *
     * <p>Empty when any element is missing or stale. The caller must not treat
     * an empty answer as a reason to construct one.
     */
    Optional<AdvertisingDecisionScope> decisionScope(UUID recommendationId);

    /**
     * Why the scope could not be resolved, in the vocabulary the console shows.
     *
     * <p>Never empty when {@link #decisionScope} is empty, and always empty when
     * it is present.
     */
    java.util.List<String> unresolvedReasons(UUID recommendationId);

    /**
     * What the advertising calculation says about this proposed change.
     *
     * <p>Available whenever the recommendation is a bid change at all, including
     * when the decision cannot be resolved — that is exactly when an operator
     * most needs to see what the case says.
     */
    Optional<AdvertisingBidProjection> bidProjection(UUID recommendationId);
}

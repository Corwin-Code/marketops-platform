package com.mimococo.marketops.advertisingefficiency;

/**
 * Which way a controlled bid change moves, and under whose authority.
 *
 * <p>The three directions are gated independently. Evidence that Ozon accepts a
 * protection decrease says nothing about whether it accepts an optimization
 * increase, and evidence about either says nothing about Wildberries. Keeping
 * them as separate constants rather than a signed number is what makes
 * "enabled for decreases only" expressible.
 */
public enum BidDirection {

    /** Lower the bid to stop proven harm. */
    PROTECTION_DECREASE,

    /** Raise the bid to capture proven recoverable contribution. */
    OPTIMIZATION_INCREASE,

    /** Restore the exact captured prior bid inside the original action lineage. */
    EXACT_PRIOR_BID_COMPENSATION;

    /** The evidence purpose a command in this direction must satisfy. */
    public DecisionPurpose purpose() {
        return switch (this) {
            case PROTECTION_DECREASE -> DecisionPurpose.PROTECTION_BID_WRITE;
            case OPTIMIZATION_INCREASE -> DecisionPurpose.OPTIMIZATION_BID_WRITE;
            case EXACT_PRIOR_BID_COMPENSATION -> DecisionPurpose.EXACT_COMPENSATION;
        };
    }

    /** Whether this direction may ever raise a live bid. */
    public boolean increasesExposure() {
        return this == OPTIMIZATION_INCREASE;
    }
}

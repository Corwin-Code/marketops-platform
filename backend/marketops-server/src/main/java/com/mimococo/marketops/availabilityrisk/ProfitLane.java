package com.mimococo.marketops.availabilityrisk;

/**
 * Which profit authority, if any, makes a stockout worth the primary queue.
 *
 * <p>The ladder is strongest-first. A settled figure beats an operational one,
 * an operational one beats an estimate, and an estimate is never allowed to
 * present itself as either. A stockout that fails the ladder does not disappear:
 * it is routed to the profit, data or quality path that can repair it.
 */
public enum ProfitLane {

    /** Fresh, complete, positive settled contribution profit. */
    CONFIRMED_ELIGIBLE(true),

    /** Settled unavailable; fresh, complete, positive operational profit. */
    OPERATIONAL_ELIGIBLE(true),

    /** Positive only through an explicit estimate. Visible, ranked, marked. */
    PROVISIONAL(true),

    /** Stale, incomplete or conflicted profit evidence. */
    PROFIT_DATA_BLOCKED(false),

    /** Fresh, complete, zero or negative. Lifecycle cannot override this. */
    NOT_PROFITABLE(false),

    /** No profit evidence of any kind was found. */
    PROFIT_UNKNOWN(false);

    private final boolean eligibleForPrimaryQueue;

    ProfitLane(boolean eligibleForPrimaryQueue) {
        this.eligibleForPrimaryQueue = eligibleForPrimaryQueue;
    }

    /**
     * Whether a stockout in this lane belongs in the primary profitable queue.
     *
     * <p>Note that {@code PROVISIONAL} is eligible but visibly marked. Excluding
     * an estimated-positive item entirely would hide a real risk; presenting it
     * as confirmed would overstate what is known.
     */
    public boolean eligibleForPrimaryQueue() {
        return eligibleForPrimaryQueue;
    }

    /** Whether the lane is itself a defect somebody has to repair. */
    public boolean blocked() {
        return this == PROFIT_DATA_BLOCKED || this == PROFIT_UNKNOWN;
    }
}

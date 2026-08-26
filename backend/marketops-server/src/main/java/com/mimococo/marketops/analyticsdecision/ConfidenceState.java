package com.mimococo.marketops.analyticsdecision;

/**
 * How much weight a computed value can carry.
 *
 * <p>Confidence is a property of the inputs rather than of the arithmetic. The
 * same formula over settled facts and over accrued estimates produces the same
 * number and two very different licences to act on it, which is why a high-risk
 * write requires a stronger state than an analysis does.
 */
public enum ConfidenceState {

    /** Every input was a confirmed canonical fact. */
    CANONICAL_CONFIRMED,

    /** Inputs are canonical but some are still accrued rather than settled. */
    CANONICAL_PENDING_SETTLEMENT,

    /** An explicit, versioned estimate contributed. */
    ESTIMATED_EXPLAINED,

    /** The freshest contributing input is older than its domain allows. */
    STALE,

    /** A required input was missing. */
    INCOMPLETE,

    /** Contributing inputs disagreed with each other. */
    CONFLICTED,

    /** Nothing is known about the inputs. */
    UNKNOWN;

    /**
     * Whether this state is strong enough for a high-risk platform write.
     *
     * <p>Only confirmed canonical inputs qualify. An estimate is enough to
     * explain a situation and to raise a task; it is not enough to change a
     * price on a marketplace.
     */
    public boolean sufficientForWrite() {
        return this == CANONICAL_CONFIRMED;
    }
}

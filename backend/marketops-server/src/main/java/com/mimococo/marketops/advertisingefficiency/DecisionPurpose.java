package com.mimococo.marketops.advertisingefficiency;

/**
 * What a piece of evidence is about to be used for.
 *
 * <p>Freshness and qualification are answered per purpose, not per fact, because
 * the same spend report can be perfectly good for showing a queue row and
 * nowhere near good enough for moving a live bid. Each constant carries a
 * strictness ordinal, and {@link #atLeastAsStrictAs(DecisionPurpose)} is what
 * makes the Contract's monotonicity rule checkable: a write purpose may never
 * accept evidence a recommendation purpose would refuse.
 *
 * <p>The ordinals are not a total order over every pair — an early
 * completed-sales outcome and an optimization write are not comparable, and
 * pretending they were would invent a constraint the Contract does not state.
 * Only the pairs named in {@link #feeds()} are ordered.
 */
public enum DecisionPurpose {

    /** Showing a case in the live queue. */
    QUEUE_OBSERVATION(0, false),

    /** Raising or updating an accountable task. */
    TASK_ACTIVATION(1, false),

    /** Producing a deterministic protection recommendation. */
    PROTECTION_RECOMMENDATION(2, false),

    /** Producing a deterministic optimization recommendation. */
    OPTIMIZATION_RECOMMENDATION(3, false),

    /** Transmitting a protection bid decrease. */
    PROTECTION_BID_WRITE(4, true),

    /** Transmitting an optimization bid increase. */
    OPTIMIZATION_BID_WRITE(5, true),

    /** Transmitting an exact prior-bid compensation. */
    EXACT_COMPENSATION(6, true),

    /** Judging the early completed-sales safety guard after an action. */
    EARLY_COMPLETED_SALES_OUTCOME(2, false),

    /** Judging the thirty-day retained-sales protection result. */
    FINAL_RETAINED_SALES_OUTCOME(3, false),

    /** Judging the settled financial confirmation. */
    SETTLED_FINANCIAL_OUTCOME(4, false);

    private final int strictness;
    private final boolean externalSideEffect;

    DecisionPurpose(int strictness, boolean externalSideEffect) {
        this.strictness = strictness;
        this.externalSideEffect = externalSideEffect;
    }

    /** How demanding this purpose is relative to the purposes that feed it. */
    public int strictness() {
        return strictness;
    }

    /**
     * Whether satisfying this purpose can cause something to happen outside
     * MarketOps.
     *
     * <p>The three write purposes are the only ones that can, and they are the
     * only ones the transmission-boundary revalidation applies to.
     */
    public boolean externalSideEffect() {
        return externalSideEffect;
    }

    /** Whether this purpose demands at least as much as {@code weaker}. */
    public boolean atLeastAsStrictAs(DecisionPurpose weaker) {
        return weaker != null && strictness >= weaker.strictness;
    }

    /**
     * The purpose whose evidence this one builds on, or {@code null} when it
     * stands alone.
     *
     * <p>These pairs are the complete set the monotonicity rule checks. A pair
     * that is not listed here is a pair the Contract does not order, and
     * inventing an order for it would be an expansion.
     */
    public DecisionPurpose feeds() {
        return switch (this) {
            case TASK_ACTIVATION -> QUEUE_OBSERVATION;
            case PROTECTION_RECOMMENDATION, OPTIMIZATION_RECOMMENDATION -> TASK_ACTIVATION;
            case PROTECTION_BID_WRITE -> PROTECTION_RECOMMENDATION;
            case OPTIMIZATION_BID_WRITE -> OPTIMIZATION_RECOMMENDATION;
            case EXACT_COMPENSATION -> PROTECTION_BID_WRITE;
            default -> null;
        };
    }
}

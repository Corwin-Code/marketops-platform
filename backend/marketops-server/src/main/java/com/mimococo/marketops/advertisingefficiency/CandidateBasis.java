package com.mimococo.marketops.advertisingefficiency;

/**
 * Why a particular bid candidate is the number it is.
 *
 * <p>Every Impact Preview states this, because the two bases justify very
 * different claims and a person approving a change is entitled to know which
 * one they are being asked to trust.
 *
 * <p>{@code MAX_CPC_BOUNDED} rests on a write-grade economic ceiling and can
 * support a claim about profitability. {@code CAUSE_BOUND_PROTECTION_STEP}
 * rests on nothing of the sort: it exists precisely because no write-grade
 * ceiling could be computed, and it justifies limiting current exposure and
 * nothing else. A Preview that let the second be read as the first would be the
 * most expensive kind of mistake this Slice can make.
 */
public enum CandidateBasis {

    /**
     * A write-grade Max CPC exists and bounds the candidate.
     *
     * <p>An increase may not exceed the conservative provider-valid value below
     * the ceiling. Protection may use the ceiling as an economic reference, and
     * still does not treat it as an automatic target.
     */
    MAX_CPC_BOUNDED(true),

    /**
     * No write-grade Max CPC exists, and one bounded lower candidate is generated
     * from the exact versioned danger cause.
     *
     * <p>Only ever a decrease, only ever under Protection, and only when the
     * missing conversion evidence cannot reverse the proven direction. The
     * resulting Preview must state that the target limits exposure and proves
     * nothing about optimality, profitability or health.
     */
    CAUSE_BOUND_PROTECTION_STEP(false);

    private final boolean supportsEconomicClaim;

    CandidateBasis(boolean supportsEconomicClaim) {
        this.supportsEconomicClaim = supportsEconomicClaim;
    }

    /** Whether a candidate on this basis may be described as economically justified. */
    public boolean supportsEconomicClaim() {
        return supportsEconomicClaim;
    }

    /** Whether this basis may generate an increase. */
    public boolean permitsIncrease() {
        return this == MAX_CPC_BOUNDED;
    }
}

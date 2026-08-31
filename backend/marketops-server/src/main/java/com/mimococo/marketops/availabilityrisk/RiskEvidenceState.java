package com.mimococo.marketops.availabilityrisk;

/**
 * What kind of evidence a calculated risk rests on.
 *
 * <p>This is separate from the lane on purpose. "About to run out" and "we are
 * confident about that" are two different claims, and a product that renders
 * them with the same weight teaches its operators to distrust all of it.
 */
public enum RiskEvidenceState {

    /** Fresh, complete, unconflicted evidence of record. */
    CONFIRMED(true),

    /** Fresh and complete, but from the operational rather than settled source. */
    OPERATIONAL(true),

    /** A conservative lower bound already proves danger; the full picture is not known. */
    PROVISIONAL(false),

    /** The last eligible answer, carried forward for a bounded period. */
    CARRIED_FORWARD(false),

    /** A decision-determinative fact is missing. */
    DATA_BLOCKED(false),

    /** No valid policy version resolves for the required scope. */
    POLICY_BLOCKED(false),

    /** Two attributable sources disagree and neither wins deterministically. */
    CONFLICTED(false),

    /** The evidence existed but is older than its freshness bound. */
    STALE(false),

    /** Nothing attributable was found at all. */
    UNKNOWN(false);

    private final boolean sufficientForSafety;

    RiskEvidenceState(boolean sufficientForSafety) {
        this.sufficientForSafety = sufficientForSafety;
    }

    /**
     * Whether this evidence may support a {@code HEALTHY} company answer.
     *
     * <p>Only confirmed and operational evidence may. This is the code-side half
     * of the database constraint that refuses to store any other combination, so
     * the rule holds whether a row arrives through the service or through a
     * repair script.
     */
    public boolean sufficientForSafety() {
        return sufficientForSafety;
    }
}

package com.mimococo.marketops.availabilityrisk;

/**
 * How urgent a calculated availability risk is.
 *
 * <p>The lanes are deliberately not a single severity number. {@code REVIEW} and
 * {@code UNRESOLVED} are not "less urgent than HIGH"; they are statements that
 * the evidence cannot support an urgency claim at all, and collapsing them into
 * an ordinary severity is how a data defect becomes a silent {@code HEALTHY}.
 *
 * <p>An accepted exception is a separate disposition and never replaces a lane.
 * A risk that somebody has agreed to live with is still the risk it was.
 */
public enum AvailabilityLane {

    /** Enough proven supply for the horizon, on evidence good enough to say so. */
    HEALTHY(0),

    /** Worth watching. Visible in the queue; raises no task on its own. */
    WATCH(1),

    /** Materially at risk. Raises work once the condition is sustained. */
    HIGH(2),

    /** Running out now, or already unavailable. Always raises work. */
    CRITICAL(3),

    /** The evidence needs a human judgement before an urgency claim is possible. */
    REVIEW(2),

    /** A decision-determinative fact is missing; no urgency can be claimed. */
    UNRESOLVED(2);

    private final int severityOrdinal;

    AvailabilityLane(int severityOrdinal) {
        this.severityOrdinal = severityOrdinal;
    }

    /**
     * How severe this lane is when choosing which child a parent card shows.
     *
     * <p>{@code REVIEW} and {@code UNRESOLVED} rank alongside {@code HIGH} rather
     * than below {@code WATCH}: not knowing whether a profitable SKU is about to
     * run out deserves attention comparable to knowing that it is.
     */
    public int severityOrdinal() {
        return severityOrdinal;
    }

    /** Whether this lane represents a positive statement that supply is adequate. */
    public boolean safe() {
        return this == HEALTHY;
    }

    /** Whether this lane exists because the evidence was insufficient. */
    public boolean evidenceLimited() {
        return this == REVIEW || this == UNRESOLVED;
    }

    /** The more severe of two lanes, preferring {@code other} only on a strict win. */
    public AvailabilityLane mostSevere(AvailabilityLane other) {
        if (other == null) {
            return this;
        }
        return other.severityOrdinal > severityOrdinal ? other : this;
    }
}

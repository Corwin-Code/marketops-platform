package com.mimococo.marketops.availabilityrisk.internal.domain;

/**
 * The three observation windows the accepted demand policy reasons over.
 *
 * <p>They are fixed rather than configurable because the policy that chooses
 * between them is what carries the judgement. A configurable window set would
 * let the same policy version mean different things in two organizations.
 */
public enum DemandWindow {

    /** The last seven days. Reacts fastest; the noisiest. */
    D7(7),

    /** The last fourteen days. */
    D14(14),

    /** The last thirty days. The most stable baseline. */
    D30(30);

    private final int days;

    DemandWindow(int days) {
        this.days = days;
    }

    /** How many days the window spans. */
    public int days() {
        return days;
    }
}

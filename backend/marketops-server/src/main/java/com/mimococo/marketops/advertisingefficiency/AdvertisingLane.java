package com.mimococo.marketops.advertisingefficiency;

/**
 * What the honest state of one advertising object is right now.
 *
 * <p>The four lanes answer four different questions, and the reason they are not
 * one severity number is that three of them are not about severity at all.
 * {@code DATA_REPAIR} is not "less urgent than PROTECTION"; it is a statement
 * that a decision-determinative fact is missing, and collapsing it into an
 * ordinary severity is how a broken spend feed becomes a quiet {@code WATCH}
 * while the money keeps leaving. {@code WATCH} is not "a small opportunity"; it
 * is a statement that a signal exists and has not yet earned the right to
 * become work.
 *
 * <p>An accepted exception is a separate disposition and never replaces a lane.
 * A risk somebody has agreed to live with is still the risk it was.
 */
public enum AdvertisingLane {

    /** Fresh one-sided proof of continuing harm. Always raises accountable work. */
    PROTECTION(3),

    /** A decision-determinative fact is missing, stale, conflicted or incomplete. */
    DATA_REPAIR(2),

    /** A complete, fresh, sustained and material opportunity with no hard block. */
    OPTIMIZATION(1),

    /** Visible but immature, unsustained or immaterial. Raises no task on its own. */
    WATCH(0);

    private final int laneBand;

    AdvertisingLane(int laneBand) {
        this.laneBand = laneBand;
    }

    /**
     * The band this lane occupies in the canonical rank.
     *
     * <p>Protection sits above Data Repair, which sits above Optimization,
     * because a proven loss outranks a missing fact and a missing fact outranks
     * an opportunity. The bands are separated widely enough that no commercial
     * score can reach across one — see {@code AdPriorityPolicy}.
     */
    public int laneBand() {
        return laneBand;
    }

    /** Whether this lane raises an accountable task without further qualification. */
    public boolean raisesWorkUnconditionally() {
        return this == PROTECTION;
    }

    /** Whether this lane exists because evidence was missing rather than bad. */
    public boolean evidenceLimited() {
        return this == DATA_REPAIR;
    }

    /** Whether this lane may ever carry an executable recommendation. */
    public boolean actionable() {
        return this == PROTECTION || this == DATA_REPAIR || this == OPTIMIZATION;
    }
}

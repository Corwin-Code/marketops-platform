package com.mimococo.marketops.marketplaceintegration;

/**
 * Where one advertising bid command has got to.
 *
 * <p>Sixteen states, and none of them collapse into a generic success. Provider
 * acceptance, a matched readback, a protection outcome and a settled
 * confirmation are four different claims, and a product that reported them as
 * one would be telling an operator that a bid landed when all it knows is that a
 * server returned 200.
 *
 * <p>{@link #LATER_CHANGE_OR_MISMATCH_INVESTIGATION} is the state that exists
 * because the alternative is worse. Something outside MarketOps now owns that
 * bid; guessing which of several things it was, and acting on the guess, would
 * turn one unexplained change into two.
 */
public enum AdBidCommandState {

    /** Created and waiting for a worker. Nothing has been sent. */
    PENDING(false, false, false),

    /** Claimed by a worker. Still nothing sent. */
    LEASED(false, true, false),

    /** About to call, or calling. */
    EXECUTING(false, true, false),

    /** The platform accepted asynchronous work that has not finished. */
    PLATFORM_PENDING(false, true, false),

    /** Waiting to observe what the platform now holds. */
    READBACK_PENDING(false, true, false),

    /** A readback observed the exact approved target. The only success. */
    READBACK_MATCHED(true, false, false),

    /** A retriable condition; a worker will claim it again after the delay. */
    RETRY_WAIT(false, false, false),

    /** The call was not answered conclusively. Never retried as a write. */
    UNKNOWN_REQUIRES_READBACK(false, false, true),

    /** A readback observed the captured prior bid. The change did not land. */
    READBACK_MISMATCH(false, false, true),

    /** A readback observed something nothing in this lineage wrote. */
    LATER_CHANGE_OR_MISMATCH_INVESTIGATION(false, false, true),

    /** An operator has taken it out of automatic handling. */
    MANUAL_RESOLUTION(false, false, true),

    /** The platform refused permanently, or the retry budget ran out. */
    FAILED_FINAL(true, false, false),

    /** A quarantine or kill stopped it before anything was sent. */
    TERMINATED_WITHOUT_PROVIDER_CALL(true, false, false),

    /** An operator authorised restoring the captured prior bid. */
    COMPENSATION_PENDING(false, true, true),

    /** The prior bid was restored and observed. */
    COMPENSATED(true, false, false),

    /** The restore could not be completed. */
    COMPENSATION_FAILED(true, false, true);

    private final boolean terminal;
    private final boolean leaseHeld;
    private final boolean needsOperator;

    AdBidCommandState(boolean terminal, boolean leaseHeld, boolean needsOperator) {
        this.terminal = terminal;
        this.leaseHeld = leaseHeld;
        this.needsOperator = needsOperator;
    }

    /** Whether nothing further will happen without a new command. */
    public boolean terminal() {
        return terminal;
    }

    /** Whether a worker holds this command right now. */
    public boolean leaseHeld() {
        return leaseHeld;
    }

    /**
     * Whether a person has to look at this.
     *
     * <p>Drives the operator queue. A command in one of these states is not
     * progressing on its own and will not start.
     */
    public boolean needsOperator() {
        return needsOperator;
    }

    /** Whether this command still counts against the unresolved-write capacity. */
    public boolean consumesUnresolvedCapacity() {
        return needsOperator && this != COMPENSATION_PENDING;
    }
}

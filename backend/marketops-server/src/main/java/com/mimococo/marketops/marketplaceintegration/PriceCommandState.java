package com.mimococo.marketops.marketplaceintegration;

/**
 * Where a price command stands on its way to a marketplace.
 *
 * <p>The allowed moves are held in the database as data rather than here, so a
 * transition nobody reviewed cannot be reached by a defect in this code. What
 * this type adds is the classification the rest of the product needs: whether a
 * command is finished, whether it still holds a worker's claim, and whether a
 * person has to look at it.
 *
 * <p>The two states worth naming carefully are the ones that describe not
 * knowing. {@link #UNKNOWN_REQUIRES_READBACK} means the platform may or may not
 * have applied the change; there is no path from it back to executing, because
 * repeating a write nobody can account for is how a price gets changed twice.
 * {@link #READBACK_MISMATCH} means the platform holds something other than what
 * was intended, which is a fact about the world rather than a failure of the
 * call.
 */
public enum PriceCommandState {

    /** Waiting for a worker. */
    PENDING,

    /** Claimed by a worker, not yet acted on. */
    LEASED,

    /** The platform call is being made. */
    EXECUTING,

    /** The platform accepted the request and is working on it. */
    PLATFORM_PENDING,

    /** The platform has answered; what it now holds must be observed. */
    READBACK_PENDING,

    /** A readback observed the intended value. */
    SUCCEEDED,

    /** A retriable condition occurred; waiting to try again. */
    RETRY_WAIT,

    /** The result cannot be classified; only a readback or a person resolves it. */
    UNKNOWN_REQUIRES_READBACK,

    /** A readback observed something other than the intended value. */
    READBACK_MISMATCH,

    /** Taken out of automatic handling by an operator. */
    MANUAL_RESOLUTION,

    /** Finished without the change being applied. */
    FAILED_FINAL,

    /** Restoring the previous value was authorized. */
    COMPENSATION_PENDING,

    /** The previous value was restored and read back. */
    COMPENSATED,

    /** The restore could not be completed. */
    COMPENSATION_FAILED;

    /** Whether nothing further will happen without an operator. */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED_FINAL || this == COMPENSATED
                || this == COMPENSATION_FAILED;
    }

    /** Whether a worker currently holds this command. */
    public boolean leaseHeld() {
        return this == LEASED || this == EXECUTING || this == PLATFORM_PENDING
                || this == READBACK_PENDING || this == COMPENSATION_PENDING;
    }

    /** Whether a person has to decide what happens next. */
    public boolean needsOperator() {
        return this == UNKNOWN_REQUIRES_READBACK || this == READBACK_MISMATCH
                || this == MANUAL_RESOLUTION;
    }
}

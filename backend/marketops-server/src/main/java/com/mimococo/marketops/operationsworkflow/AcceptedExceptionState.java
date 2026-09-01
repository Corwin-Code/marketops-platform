package com.mimococo.marketops.operationsworkflow;

/**
 * Where an accepted-risk request stands.
 *
 * <p>{@code AUTHORITY_BLOCKED} is a first-class state rather than an error,
 * because the request really was made and the answer really is "nobody here can
 * grant this". Recording it keeps the attempt auditable while the ordinary risk
 * stays exactly as active as it was.
 */
public enum AcceptedExceptionState {

    /** Submitted with its evidence, waiting for a decision. */
    REQUESTED,

    /** No valid, unambiguous approval authority resolves. The risk stays active. */
    AUTHORITY_BLOCKED,

    /** Granted, bounded and in force. The calculated risk is unchanged. */
    ACTIVE,

    /** Refused. */
    REJECTED,

    /** The granted period ended. */
    EXPIRED,

    /** Something the grant depended on stopped being true. */
    INVALIDATED,

    /** Withdrawn by the requester before a decision. */
    WITHDRAWN;

    /** Whether this acceptance still occupies the one-live-acceptance slot. */
    public boolean occupying() {
        return this == REQUESTED || this == AUTHORITY_BLOCKED || this == ACTIVE;
    }

    /** Whether an acceptance in this state actually disposes of the risk. */
    public boolean inForce() {
        return this == ACTIVE;
    }
}

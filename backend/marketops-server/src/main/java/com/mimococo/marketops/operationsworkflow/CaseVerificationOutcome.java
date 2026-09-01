package com.mimococo.marketops.operationsworkflow;

/**
 * What a fresh cause-specific observation showed.
 *
 * <p>Four outcomes rather than two, because "not yet" and "no" are different
 * answers with different consequences: one keeps the outcome clock running,
 * the other returns the case to somebody.
 */
public enum CaseVerificationOutcome {

    /** The risk improved and stayed improved through the governed window. */
    VERIFIED,

    /** Not enough of the window has elapsed to say. */
    CONTINUING,

    /** The action did not improve the risk. */
    FAILED,

    /** The risk had improved and has returned. */
    REGRESSED
}

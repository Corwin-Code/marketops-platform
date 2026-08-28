package com.mimococo.marketops.analyticsdecision;

/**
 * Whether a computed metric produced a number, and why not when it did not.
 *
 * <p>The three states are kept apart because they lead to different operator
 * actions. A metric no source publishes is a coverage problem; one whose
 * definition has no answer for these inputs is a business situation; and only an
 * available metric is a number anybody may act on. None of the three is ever
 * rendered as zero.
 */
public enum ValueState {

    /** A number was computed. */
    AVAILABLE,

    /** No source publishes the inputs this metric needs. */
    NOT_AVAILABLE,

    /** The definition has no answer for these inputs, such as a zero denominator. */
    UNDEFINED
}

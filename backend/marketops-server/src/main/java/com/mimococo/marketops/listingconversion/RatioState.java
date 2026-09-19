package com.mimococo.marketops.listingconversion;

/**
 * Whether a set ratio has a value.
 *
 * <p>UNDEFINED is a zero denominator and NOT_AVAILABLE is missing maturity or
 * eligibility. Neither is zero, and neither is a number the console may plot.
 */
public enum RatioState {
    DEFINED,
    UNDEFINED,
    NOT_AVAILABLE
}

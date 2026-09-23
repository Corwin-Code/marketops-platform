package com.mimococo.marketops.listingconversion;

/**
 * The only grounds on which an occupation is released.
 *
 * <p>Time alone never releases anything. {@link #OUTCOME_MATURED} is the one basis that involves a
 * clock, and it needs confirmed evidence first: a description change that was applied and verified
 * (qualified manual verification or matched API readback) whose outcome observation period
 * ({@code RESPONSIBILITY_SLO.outcomeMaturityDays} of its own calibration package) has passed.
 */
public enum ReleaseBasis {
    STOP_EVIDENCE,
    OBLIGATION_CLEARED,
    NOT_APPLIED_PROVEN,
    OUTCOME_MATURED
}

package com.mimococo.marketops.testfixture.violation.moduleinternals.beta;

import com.mimococo.marketops.testfixture.violation.moduleinternals.alpha.internal.AlphaInternalDetail;

/**
 * Reaches past the alpha module's published surface into its implementation.
 *
 * <p>This is the arrangement the encapsulation rule exists to reject.
 */
public final class BetaReadsAlphaInternals {

    private final AlphaInternalDetail borrowed = new AlphaInternalDetail();

    /** Return a value obtained from another module's private implementation. */
    public String describe() {
        return borrowed.detail();
    }
}

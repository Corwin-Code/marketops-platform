package com.mimococo.marketops.testfixture.violation.cycle.beta;

import com.mimococo.marketops.testfixture.violation.cycle.alpha.AlphaUsesBeta;

/** The other half of the dependency circle. */
public final class BetaUsesAlpha {

    /** Ask the other module for a value. */
    public String describe(AlphaUsesBeta other) {
        return "beta sees " + other.name();
    }

    /** Name this participant. */
    public String name() {
        return "beta";
    }
}

package com.mimococo.marketops.testfixture.violation.cycle.alpha;

import com.mimococo.marketops.testfixture.violation.cycle.beta.BetaUsesAlpha;

/** One half of a dependency circle between two modules. */
public final class AlphaUsesBeta {

    /** Ask the other module for a value. */
    public String describe(BetaUsesAlpha other) {
        return "alpha sees " + other.name();
    }

    /** Name this participant. */
    public String name() {
        return "alpha";
    }
}

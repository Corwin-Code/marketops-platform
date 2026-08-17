package com.mimococo.marketops.testfixture.violation.moduleinternals.alpha.internal;

/** Private implementation of the alpha module. */
public final class AlphaInternalDetail {

    /** Return a value only the owning module is entitled to compute. */
    public String detail() {
        return "alpha";
    }
}

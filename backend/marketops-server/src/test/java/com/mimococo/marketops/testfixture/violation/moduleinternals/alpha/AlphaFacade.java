package com.mimococo.marketops.testfixture.violation.moduleinternals.alpha;

import com.mimococo.marketops.testfixture.violation.moduleinternals.alpha.internal.AlphaInternalDetail;

/** The published surface of the alpha module, which may use its own internals. */
public final class AlphaFacade {

    private final AlphaInternalDetail detail = new AlphaInternalDetail();

    /** Return the value the module is willing to publish. */
    public String describe() {
        return detail.detail();
    }
}

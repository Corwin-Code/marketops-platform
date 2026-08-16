package com.mimococo.marketops.testfixture.violation.moduleinternals.alphabeta;

import com.mimococo.marketops.testfixture.violation.moduleinternals.alpha.internal.AlphaInternalDetail;

/** Deliberate prefix-collision access: alphabeta is not the alpha module. */
public class AlphaBetaReadsAlphaInternals {
    private final AlphaInternalDetail detail = new AlphaInternalDetail();
}

package com.mimococo.marketops.analyticsdecision;

/** The four non-interchangeable outcomes of checking one required fee family. */
public enum FeeCoverageState {
    PRESENT_NONZERO,
    PRESENT_EXPLICIT_ZERO,
    VERIFIED_NOT_APPLICABLE,
    MISSING_OR_INCOMPLETE
}

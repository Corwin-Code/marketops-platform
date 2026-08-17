package com.mimococo.marketops.testfixture.violation.sharedoutward.shared;

import com.mimococo.marketops.testfixture.violation.sharedoutward.reporting.ReportingType;

/**
 * A shared type that depends on a specific module.
 *
 * <p>This is the arrangement the shared-module rule exists to reject. Everything
 * may depend on the shared module, so a dependency out of it makes its target
 * reachable from every module in the system.
 */
public final class SharedTypeReachingOutward {

    private final ReportingType reporting = new ReportingType();

    /** Return a value obtained from a specific module. */
    public String describe() {
        return reporting.name();
    }
}

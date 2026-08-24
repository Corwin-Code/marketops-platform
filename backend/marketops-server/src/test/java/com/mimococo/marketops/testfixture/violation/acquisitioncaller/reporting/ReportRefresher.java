package com.mimococo.marketops.testfixture.violation.acquisitioncaller.reporting;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;

/**
 * A class in another module holding the acquisition doorway.
 *
 * <p>This is the arrangement the single-caller rule exists to reject: a module
 * that can reach the port can emit outbound calls without the owning module's
 * scheduler, permits or evidence ever seeing them.
 */
public final class ReportRefresher {

    private final AcquisitionPort acquisition;

    public ReportRefresher(AcquisitionPort acquisition) {
        this.acquisition = acquisition;
    }

    /** Report which doorway this refresher would use. */
    public String doorway() {
        return acquisition.getClass().getName();
    }
}

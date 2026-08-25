package com.mimococo.marketops.testfixture.violation.acquisitioncaller.reporting;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;

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

    /** Deliberately bypass the sole authority-aware executor. */
    public AcquisitionResult refresh(AcquisitionRequest request) {
        return acquisition.acquire(request);
    }
}

package com.mimococo.marketops.testfixture.violation.insideacquisition.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;

/** Deliberately bypasses the executor from inside the owning module. */
public final class BypassAcquisitionCaller {

    private final BypassAcquisitionAdapter acquisition;

    public BypassAcquisitionCaller(BypassAcquisitionAdapter acquisition) {
        this.acquisition = acquisition;
    }

    /** Invoke the port without the authority expiry check. */
    public AcquisitionResult bypass(AcquisitionRequest request) {
        return acquisition.acquire(request);
    }
}

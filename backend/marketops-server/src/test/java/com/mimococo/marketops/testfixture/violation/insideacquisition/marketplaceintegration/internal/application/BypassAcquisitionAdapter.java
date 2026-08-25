package com.mimococo.marketops.testfixture.violation.insideacquisition.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import java.time.Instant;

/** A concrete owning-module adapter used to expose direct-call bypasses. */
public final class BypassAcquisitionAdapter implements AcquisitionPort {

    @Override
    public AcquisitionResult acquire(AcquisitionRequest request) {
        return new AcquisitionResult(
                new byte[0], "NOOP",
                AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE, Instant.EPOCH);
    }
}

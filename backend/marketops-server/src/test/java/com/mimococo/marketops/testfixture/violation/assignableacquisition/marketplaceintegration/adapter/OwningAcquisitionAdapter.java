package com.mimococo.marketops.testfixture.violation.assignableacquisition.marketplaceintegration.adapter;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import java.time.Instant;

/** Owning-module base used to prove an outside inherited implementation is rejected. */
public class OwningAcquisitionAdapter implements AcquisitionPort {

    @Override
    public AcquisitionResult acquire(AcquisitionRequest request) {
        return new AcquisitionResult(
                new byte[0], "NOOP",
                AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE, Instant.EPOCH);
    }
}

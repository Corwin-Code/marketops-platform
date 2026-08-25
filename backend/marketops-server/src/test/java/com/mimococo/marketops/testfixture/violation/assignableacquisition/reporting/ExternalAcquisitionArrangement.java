package com.mimococo.marketops.testfixture.violation.assignableacquisition.reporting;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import com.mimococo.marketops.testfixture.violation.assignableacquisition.marketplaceintegration.adapter.OwningAcquisitionAdapter;
import java.time.Instant;

interface ExternalAcquisitionPort extends AcquisitionPort {
}

final class ExternalSubinterfaceAdapter implements ExternalAcquisitionPort {

    @Override
    public AcquisitionResult acquire(AcquisitionRequest request) {
        return new AcquisitionResult(
                new byte[0], "NOOP",
                AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE, Instant.EPOCH);
    }
}

final class ExternalInheritedAcquisitionAdapter extends OwningAcquisitionAdapter {
}

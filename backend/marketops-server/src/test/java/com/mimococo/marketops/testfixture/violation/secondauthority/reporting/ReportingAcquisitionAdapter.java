package com.mimococo.marketops.testfixture.violation.secondauthority.reporting;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import java.time.Instant;

/**
 * An acquisition port implemented outside the owning module.
 *
 * <p>This is the arrangement the single-authority rule exists to reject: a
 * second implementing module is a second doorway to the outside, with its own
 * idea of leases, permits and evidence.
 */
public final class ReportingAcquisitionAdapter implements AcquisitionPort {

    @Override
    public AcquisitionResult acquire(AcquisitionRequest request) {
        return new AcquisitionResult(
                new byte[0], "NOOP",
                AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE, Instant.EPOCH);
    }
}

package com.mimococo.marketops.testfixture.conforming.ingestionauthority.marketplaceintegration.acquisition;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import com.mimococo.marketops.marketplaceintegration.port.AuthorizedAcquisitionExecutor;
import java.time.Instant;

/**
 * The conforming arrangement: the owning module both implements and calls the
 * acquisition port, and no controller is anywhere near it.
 */
public final class LeasedAcquisitionWorker implements AcquisitionPort {

    @Override
    public AcquisitionResult acquire(AcquisitionRequest request) {
        return new AcquisitionResult(
                new byte[0], "NOOP",
                AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE, Instant.EPOCH);
    }

    /** Bind this adapter to the sole executor that may invoke the doorway. */
    public AuthorizedAcquisitionExecutor executor() {
        return new AuthorizedAcquisitionExecutor(this);
    }
}

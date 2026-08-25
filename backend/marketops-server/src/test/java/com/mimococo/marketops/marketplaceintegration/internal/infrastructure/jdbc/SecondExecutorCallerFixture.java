package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;

/** Deliberately adds a second internal executor caller beside the JDBC gateway. */
public final class SecondExecutorCallerFixture {

    /** This bypass must be rejected by TC-ARCH-028. */
    AcquisitionResult execute(
            AuthorizedAcquisitionExecutor executor, CallAuthorityGrant grant) {
        return executor.execute(grant);
    }
}

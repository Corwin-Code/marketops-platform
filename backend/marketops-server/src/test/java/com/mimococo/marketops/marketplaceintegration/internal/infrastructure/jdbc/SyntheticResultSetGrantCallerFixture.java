package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Deliberately maps a caller-provided ResultSet outside the exact gateway. */
public final class SyntheticResultSetGrantCallerFixture {

    /** This shape-valid-looking seam must be rejected by TC-ARCH-026. */
    public AcquisitionResult execute(
            ResultSet syntheticRows, AuthorizedAcquisitionExecutor executor)
            throws SQLException {
        CallAuthorityGrant forged = new CallAuthorityGrantMapper().map(syntheticRows);
        return executor.execute(forged);
    }
}

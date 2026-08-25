package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

/** Maps the current row of the database grant primitive to its internal one-shot value. */
final class CallAuthorityGrantMapper {

    /**
     * Read every identity and control proof from the structured database result.
     * No caller-supplied value participates in construction.
     */
    CallAuthorityGrant map(ResultSet rows) throws SQLException {
        Array scopeArray = rows.getArray("control_epoch_scopes");
        Array valueArray = rows.getArray("control_epoch_values");
        try {
            String[] scopes = (String[]) scopeArray.getArray();
            Long[] values = (Long[]) valueArray.getArray();
            return new CallAuthorityGrant(
                    rows.getObject("decision_id", UUID.class),
                    rows.getObject("job_id", UUID.class),
                    rows.getObject("run_id", UUID.class),
                    rows.getLong("fence_token"),
                    rows.getString("lease_owner"),
                    rows.getString("platform_code"),
                    rows.getObject("endpoint_id", UUID.class),
                    rows.getObject("credential_id", UUID.class),
                    rows.getObject("scope_grant_id", UUID.class),
                    rows.getInt("call_seq"),
                    rows.getTimestamp("granted_at").toInstant(),
                    rows.getTimestamp("call_authority_expires_at").toInstant(),
                    rows.getTimestamp("run_lease_expires_at").toInstant(),
                    rows.getTimestamp("server_policy_deadline").toInstant(),
                    Arrays.asList(scopes),
                    Arrays.asList(values),
                    rows.getString("boundary_set_digest"));
        } finally {
            scopeArray.free();
            valueArray.free();
        }
    }
}

package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthValueSource;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The recorded facts an outbound platform call is built from.
 *
 * <p>Every query filters on verification and status inside the database rather
 * than in the caller. An endpoint that has not been verified, a platform profile
 * that has been retired and an authentication header nobody has checked are all
 * invisible here, so an adapter that finds nothing cannot proceed by accident —
 * it has nothing to proceed with.
 */
@Repository
public class PlatformCallSpecRepository {

    private final JdbcClient jdbc;

    PlatformCallSpecRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The call specification for one endpoint, when everything it needs is
     * verified.
     *
     * <p>An empty result is the fail-closed answer and covers every reason at
     * once: the endpoint does not exist, is retired, is unverified, has no
     * recorded method or path, or its platform has no active profile.
     */
    public Optional<EndpointCallSpec> findVerifiedSpec(UUID endpointId) {
        return jdbc.sql("""
                        SELECT endpoint.id, endpoint.platform_code, endpoint.endpoint_code,
                               profile.base_url, endpoint.http_method, endpoint.path_template,
                               endpoint.query_template, endpoint.body_template,
                               endpoint.response_content_type, endpoint.continuation_pointer,
                               endpoint.pagination_model,
                               endpoint.rate_limit_per_minute, profile.request_timeout_ms,
                               profile.max_response_bytes
                          FROM platform.platform_endpoint AS endpoint
                          JOIN platform.platform_api_profile AS profile
                            ON profile.platform_code = endpoint.platform_code
                         WHERE endpoint.id = :endpointId
                           AND endpoint.status = 'ACTIVE'
                           AND endpoint.deprecated_at IS NULL
                           AND endpoint.verification_state = 'VERIFIED'
                           AND endpoint.http_method IS NOT NULL
                           AND endpoint.path_template IS NOT NULL
                           AND profile.status = 'ACTIVE'
                           AND profile.verification_state = 'VERIFIED'
                        """)
                .param("endpointId", endpointId)
                .query(PlatformCallSpecRepository::mapSpec)
                .optional();
    }

    /** The verified authentication headers of one platform, in recorded order. */
    public List<AuthHeaderSpec> verifiedAuthHeaders(String platformCode) {
        return jdbc.sql("""
                        SELECT header_name, value_source, value_template,
                               credential_purpose, ordinal
                          FROM platform.platform_auth_header
                         WHERE platform_code = :platformCode
                           AND status = 'ACTIVE'
                           AND verification_state = 'VERIFIED'
                         ORDER BY ordinal, header_name
                        """)
                .param("platformCode", platformCode)
                .query((rows, rowNumber) -> new AuthHeaderSpec(
                        rows.getString("header_name"),
                        AuthValueSource.valueOf(rows.getString("value_source")),
                        rows.getString("value_template"),
                        rows.getString("credential_purpose"),
                        rows.getInt("ordinal")))
                .list();
    }

    /**
     * The opaque secret reference of one credential, when the credential is
     * currently valid for the purpose the header requires.
     *
     * <p>Validity is evaluated here against the database clock. A credential
     * whose window has passed is invisible, so an expired credential cannot be
     * used by a caller that read it a moment before it lapsed.
     */
    public Optional<String> activeSecretReference(UUID credentialId, String credentialPurpose) {
        return jdbc.sql("""
                        SELECT secret_reference FROM platform.credential_metadata
                         WHERE id = :credentialId
                           AND purpose_code = :credentialPurpose
                           AND status = 'ACTIVE'
                           AND effective_from <= clock_timestamp()
                           AND expires_at > clock_timestamp()
                        """)
                .param("credentialId", credentialId)
                .param("credentialPurpose", credentialPurpose)
                .query(String.class)
                .optional();
    }

    /** The marketplace account's own identifier for one credential's account. */
    public Optional<String> accountNativeKey(UUID credentialId) {
        return jdbc.sql("""
                        SELECT account.native_account_key
                          FROM platform.credential_metadata AS credential
                          JOIN core.marketplace_account AS account
                            ON account.id = credential.marketplace_account_id
                         WHERE credential.id = :credentialId
                           AND account.native_account_key IS NOT NULL
                        """)
                .param("credentialId", credentialId)
                .query(String.class)
                .optional();
    }

    /** The acquisition cursor of one job, when it holds a position. */
    public Optional<String> checkpointPosition(UUID jobId) {
        return jdbc.sql("""
                        SELECT position_value FROM ops.ingestion_checkpoint
                         WHERE job_id = :jobId AND position_value IS NOT NULL
                        """)
                .param("jobId", jobId)
                .query(String.class)
                .optional();
    }

    private static EndpointCallSpec mapSpec(ResultSet rows, int rowNumber) throws SQLException {
        // wasNull reports on the column read immediately before it, so the
        // absence of a rate limit is captured here rather than inside the
        // constructor call, where later reads would have replaced the flag.
        int rateLimitValue = rows.getInt("rate_limit_per_minute");
        Integer rateLimit = rows.wasNull() ? null : rateLimitValue;
        return new EndpointCallSpec(
                rows.getObject("id", UUID.class),
                rows.getString("platform_code"),
                rows.getString("endpoint_code"),
                rows.getString("base_url"),
                rows.getString("http_method"),
                rows.getString("path_template"),
                rows.getString("query_template"),
                rows.getString("body_template"),
                rows.getString("response_content_type"),
                rows.getString("continuation_pointer"),
                rows.getString("pagination_model"),
                rateLimit,
                rows.getInt("request_timeout_ms"),
                rows.getLong("max_response_bytes"));
    }
}

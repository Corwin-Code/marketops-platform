package com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Registration and verification of model providers and the models they offer.
 *
 * <p>No DELETE is granted anywhere: retiring a provider is a recorded transition
 * so the invocations made while it was live stay attributable to it.
 */
@Repository
public class AiProviderRepository {

    private final JdbcClient jdbc;

    AiProviderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Register a provider in its unverified, inactive state. */
    public void insertProvider(UUID id, String providerCode, String displayName,
                               String serviceRegionLabel, String ownerLabel, Instant now) {
        jdbc.sql("""
                        INSERT INTO ops.ai_provider (
                            id, provider_code, display_name, service_region_label,
                            eligibility_state, owner_label, status, created_at, updated_at,
                            version)
                        VALUES (:id, :providerCode, :displayName, :serviceRegionLabel,
                            'UNVERIFIED', :ownerLabel, 'RETIRED', :now, :now, 0)
                        """)
                .param("id", id)
                .param("providerCode", providerCode)
                .param("displayName", displayName)
                .param("serviceRegionLabel", serviceRegionLabel)
                .param("ownerLabel", ownerLabel)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Record the checked contract and the recorded call shape, and activate. */
    public boolean verifyAndActivate(UUID id, String invocationUrl, String requestTemplate,
                                     String responsePointer, String authHeaderName,
                                     String authValueTemplate, int requestTimeoutMillis,
                                     Instant verifiedAt, String evidenceRef,
                                     String verifiedSourceTitle, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.ai_provider
                        SET invocation_url = :invocationUrl,
                            request_template = :requestTemplate,
                            response_pointer = :responsePointer,
                            auth_header_name = :authHeaderName,
                            auth_value_template = :authValueTemplate,
                            request_timeout_ms = :requestTimeoutMillis,
                            eligibility_state = 'VERIFIED', last_verified_at = :verifiedAt,
                            evidence_ref = :evidenceRef,
                            verified_source_title = :verifiedSourceTitle,
                            status = 'ACTIVE', updated_at = :verifiedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("invocationUrl", invocationUrl)
                .param("requestTemplate", requestTemplate)
                .param("responsePointer", responsePointer)
                .param("authHeaderName", authHeaderName)
                .param("authValueTemplate", authValueTemplate)
                .param("requestTimeoutMillis", requestTimeoutMillis)
                .param("verifiedAt", Timestamp.from(verifiedAt))
                .param("evidenceRef", evidenceRef)
                .param("verifiedSourceTitle", verifiedSourceTitle)
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Stop calling a provider. */
    public boolean retireProvider(UUID id, Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.ai_provider
                        SET status = 'RETIRED', updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Register a model a provider offers. */
    public void insertModel(UUID id, UUID providerId, String modelCode, String displayName,
                            String secretReference, Integer maximumContextTokens,
                            Instant now) {
        jdbc.sql("""
                        INSERT INTO ops.ai_model (
                            id, provider_id, model_code, display_name, secret_reference,
                            max_context_tokens, status, created_at, updated_at, version)
                        VALUES (:id, :providerId, :modelCode, :displayName, :secretReference,
                            :maxContextTokens, 'ACTIVE', :now, :now, 0)
                        """)
                .param("id", id)
                .param("providerId", providerId)
                .param("modelCode", modelCode)
                .param("displayName", displayName)
                .param("secretReference", secretReference)
                .param("maxContextTokens", maximumContextTokens)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Every registered provider, ordered by business code. */
    public List<ProviderRow> listProviders() {
        return jdbc.sql("""
                        SELECT id, provider_code, display_name, service_region_label,
                               eligibility_state, status, owner_label, last_verified_at,
                               version
                          FROM ops.ai_provider ORDER BY provider_code
                        """)
                .query(AiProviderRepository::map)
                .list();
    }

    private static ProviderRow map(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp lastVerified = rows.getTimestamp("last_verified_at");
        return new ProviderRow(
                rows.getObject("id", UUID.class),
                rows.getString("provider_code"),
                rows.getString("display_name"),
                rows.getString("service_region_label"),
                rows.getString("eligibility_state"),
                rows.getString("status"),
                rows.getString("owner_label"),
                lastVerified == null ? null : lastVerified.toInstant(),
                rows.getLong("version"));
    }

    /**
     * One registered provider.
     *
     * @param id identifier
     * @param providerCode business code
     * @param displayName operator-facing name
     * @param serviceRegionLabel recorded service region, or {@code null}
     * @param eligibilityState how well the contract is known
     * @param status whether the gateway may call it
     * @param ownerLabel responsible owner
     * @param lastVerifiedAt when the contract was last checked, or {@code null}
     * @param version optimistic-lock version
     */
    public record ProviderRow(
            UUID id, String providerCode, String displayName, String serviceRegionLabel,
            String eligibilityState, String status, String ownerLabel, Instant lastVerifiedAt,
            long version) {
    }
}

package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads how a write is actually performed, and refuses to answer when nobody
 * has recorded it.
 *
 * <p>The lookup requires the operation, its endpoint and the platform's API
 * profile to all be verified and active at once. Any one of them missing yields
 * no specification, so an unverified write path has no way to reach a
 * marketplace — the refusal is structural rather than a check in the caller.
 */
@Repository
public class WriteOperationRepository {

    private final JdbcClient jdbc;

    WriteOperationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The verified specification for one operation, when one exists. */
    public Optional<WriteOperationSpec> verifiedOperation(UUID capabilityId,
                                                          String operation) {
        return jdbc.sql("""
                        SELECT operation.capability_id, operation.platform_code,
                               operation.operation, capability.write_result_model,
                               operation.request_template, operation.task_key_pointer,
                               operation.task_status_pointer, operation.task_success_value,
                               operation.task_failure_value, operation.observed_price_pointer,
                               operation.observed_currency_pointer,
                               endpoint.id AS endpoint_id, endpoint.endpoint_code,
                               profile.base_url, endpoint.http_method, endpoint.path_template,
                               endpoint.query_template, endpoint.body_template,
                               endpoint.response_content_type, endpoint.continuation_pointer,
                               endpoint.pagination_model, endpoint.rate_limit_per_minute,
                               profile.request_timeout_ms, profile.max_response_bytes
                          FROM platform.capability_operation AS operation
                          JOIN platform.platform_capability AS capability
                            ON capability.id = operation.capability_id
                          JOIN platform.platform_endpoint AS endpoint
                            ON endpoint.id = operation.endpoint_id
                          JOIN platform.platform_api_profile AS profile
                            ON profile.platform_code = operation.platform_code
                         WHERE operation.capability_id = :capabilityId
                           AND operation.operation = :operation
                           AND operation.status = 'ACTIVE'
                           AND operation.verification_state = 'VERIFIED'
                           AND capability.status = 'ACTIVE'
                           AND capability.verification_state = 'VERIFIED'
                           AND capability.deprecated_at IS NULL
                           AND endpoint.status = 'ACTIVE'
                           AND endpoint.verification_state = 'VERIFIED'
                           AND endpoint.deprecated_at IS NULL
                           AND profile.status = 'ACTIVE'
                           AND profile.verification_state = 'VERIFIED'
                        """)
                .param("capabilityId", capabilityId)
                .param("operation", operation)
                .query(WriteOperationRepository::map)
                .optional();
    }

    /** Register an operation shape, unverified and unreachable. */
    public void insert(UUID id, UUID capabilityId, String platformCode, String operation,
                       UUID endpointId, String requestTemplate, String acceptedPointer,
                       String taskKeyPointer, String taskStatusPointer,
                       String taskSuccessValue, String taskFailureValue,
                       String observedPricePointer, String observedCurrencyPointer,
                       String ownerLabel, Instant now) {
        jdbc.sql("""
                        INSERT INTO platform.capability_operation (
                            id, capability_id, platform_code, operation, endpoint_id,
                            request_template, accepted_pointer, task_key_pointer,
                            task_status_pointer, task_success_value, task_failure_value,
                            observed_price_pointer, observed_currency_pointer,
                            verification_state, owner_label, status, created_at, updated_at,
                            version)
                        VALUES (:id, :capabilityId, :platformCode, :operation, :endpointId,
                            :requestTemplate, :acceptedPointer, :taskKeyPointer,
                            :taskStatusPointer, :taskSuccessValue, :taskFailureValue,
                            :observedPricePointer, :observedCurrencyPointer, 'UNVERIFIED',
                            :ownerLabel, 'RETIRED', :now, :now, 0)
                        """)
                .param("id", id)
                .param("capabilityId", capabilityId)
                .param("platformCode", platformCode)
                .param("operation", operation)
                .param("endpointId", endpointId)
                .param("requestTemplate", requestTemplate)
                .param("acceptedPointer", acceptedPointer)
                .param("taskKeyPointer", taskKeyPointer)
                .param("taskStatusPointer", taskStatusPointer)
                .param("taskSuccessValue", taskSuccessValue)
                .param("taskFailureValue", taskFailureValue)
                .param("observedPricePointer", observedPricePointer)
                .param("observedCurrencyPointer", observedCurrencyPointer)
                .param("ownerLabel", ownerLabel)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Record that the shape was checked against a real source, and activate. */
    public boolean verifyAndActivate(UUID id, Instant verifiedAt, String evidenceRef,
                                     String verifiedSourceTitle, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE platform.capability_operation
                        SET verification_state = 'VERIFIED', last_verified_at = :verifiedAt,
                            evidence_ref = :evidenceRef,
                            verified_source_title = :verifiedSourceTitle,
                            status = 'ACTIVE', updated_at = :verifiedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("verifiedAt", Timestamp.from(verifiedAt))
                .param("evidenceRef", evidenceRef)
                .param("verifiedSourceTitle", verifiedSourceTitle)
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Stop performing an operation. */
    public boolean retire(UUID id, Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE platform.capability_operation
                        SET status = 'RETIRED', updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Every recorded operation of one capability. */
    public List<OperationRow> list(UUID capabilityId) {
        return jdbc.sql("""
                        SELECT id, operation, endpoint_id, verification_state, status,
                               last_verified_at, owner_label, version
                          FROM platform.capability_operation
                         WHERE capability_id = :capabilityId
                         ORDER BY operation
                        """)
                .param("capabilityId", capabilityId)
                .query(WriteOperationRepository::mapRow)
                .list();
    }

    private static WriteOperationSpec map(ResultSet rows, int rowNumber) throws SQLException {
        int rateLimitValue = rows.getInt("rate_limit_per_minute");
        Integer rateLimit = rows.wasNull() ? null : rateLimitValue;
        EndpointCallSpec endpoint = new EndpointCallSpec(
                rows.getObject("endpoint_id", UUID.class),
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
        return new WriteOperationSpec(
                rows.getObject("capability_id", UUID.class),
                rows.getString("platform_code"),
                rows.getString("operation"),
                rows.getString("write_result_model"),
                rows.getString("request_template"),
                rows.getString("task_key_pointer"),
                rows.getString("task_status_pointer"),
                rows.getString("task_success_value"),
                rows.getString("task_failure_value"),
                rows.getString("observed_price_pointer"),
                rows.getString("observed_currency_pointer"),
                endpoint);
    }

    private static OperationRow mapRow(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp verified = rows.getTimestamp("last_verified_at");
        return new OperationRow(
                rows.getObject("id", UUID.class),
                rows.getString("operation"),
                rows.getObject("endpoint_id", UUID.class),
                rows.getString("verification_state"),
                rows.getString("status"),
                verified == null ? null : verified.toInstant(),
                rows.getString("owner_label"),
                rows.getLong("version"));
    }

    /**
     * One recorded operation, as maintenance sees it.
     *
     * @param id the operation
     * @param operation what the call is for
     * @param endpointId the endpoint it goes through
     * @param verificationState how well its shape is known
     * @param status whether the write path may perform it
     * @param lastVerifiedAt when it was last checked, or {@code null}
     * @param ownerLabel responsible owner
     * @param version optimistic-lock version
     */
    public record OperationRow(UUID id, String operation, UUID endpointId,
                               String verificationState, String status, Instant lastVerifiedAt,
                               String ownerLabel, long version) {
    }
}

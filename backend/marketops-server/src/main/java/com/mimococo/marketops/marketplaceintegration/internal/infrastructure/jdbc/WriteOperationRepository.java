package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                               operation.observed_currency_pointer, operation.conditional_write_header,
                               operation.accepted_pointer, operation.accepted_value,
                               operation.task_pending_values,
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
                endpoint, rows.getString("conditional_write_header"), rows.getString("accepted_pointer"),
                rows.getString("accepted_value") == null ? null
                    : com.mimococo.marketops.shared.JsonValues.read(tools.jackson.databind.json.JsonMapper.builder().build(),
                            rows.getString("accepted_value")),
                java.util.Set.of((String[]) rows.getArray("task_pending_values").getArray()));
    }

}

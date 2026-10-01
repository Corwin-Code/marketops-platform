package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.ContentOperationSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads how a content write is performed, and answers nothing for an operation nobody verified
 * (W2). The capability, the endpoint and the platform profile must all be verified and active.
 */
@Repository
public class ContentOperationRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    ContentOperationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The verified specification of one content operation, when one exists. */
    public Optional<ContentOperationSpec> verifiedOperation(UUID capabilityId, String operation) {
        return jdbc.sql("""
                        SELECT operation.capability_id, operation.platform_code, operation.operation,
                               operation.request_template, operation.task_key_pointer,
                               operation.task_status_pointer, operation.task_success_value,
                               operation.task_failure_value, operation.task_pending_values,
                               operation.description_observed_text_pointer,
                               operation.description_response_binding::text AS binding,
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
                           AND capability.capability_code = 'listing-content-change'
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
                .query(ContentOperationRepository::map)
                .optional();
    }

    private static ContentOperationSpec map(ResultSet rows, int rowNumber) throws SQLException {
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
        String bindingText = rows.getString("binding");
        JsonNode binding = bindingText == null ? JSON.createObjectNode()
                : com.mimococo.marketops.shared.JsonValues.read(JSON, bindingText);
        return new ContentOperationSpec(
                rows.getObject("capability_id", UUID.class),
                rows.getString("platform_code"),
                rows.getString("operation"),
                rows.getString("request_template"),
                rows.getString("task_key_pointer"),
                rows.getString("task_status_pointer"),
                rows.getString("task_success_value"),
                rows.getString("task_failure_value"),
                Set.of((String[]) rows.getArray("task_pending_values").getArray()),
                textValues(binding.get("noChangeValues")),
                textOrNull(binding.get("errorsPointer")),
                rows.getString("description_observed_text_pointer"),
                textOrNull(binding.get("titlePointer")),
                endpoint);
    }

    private static Set<String> textValues(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> {
                if (value.isString()) {
                    values.add(value.asString());
                }
            });
        }
        return values;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }
}

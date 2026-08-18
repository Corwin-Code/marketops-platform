package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.ContractTestStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PaginationModel;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformEndpoint;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ReadWriteClass;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RegistryStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.TriState;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code platform.platform_endpoint}. */
@Repository
public class EndpointRepository {

    private final JdbcClient jdbc;

    EndpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new endpoint row. */
    public void insert(PlatformEndpoint endpoint) {
        jdbc.sql("""
                        INSERT INTO platform.platform_endpoint (
                            id, platform_code, endpoint_code, api_version, http_method,
                            path_template, capability_id, read_write_class, pagination_model,
                            rate_limit_per_minute, rate_limit_note, quota_note,
                            idempotency_support, late_data_behavior, freshness_expectation,
                            business_key_note, schema_version, deprecated_at,
                            replacement_endpoint_id, verification_state, last_verified_at,
                            evidence_ref, verified_source_title, owner_label,
                            contract_test_status, status, created_at, updated_at, version)
                        VALUES (:id, :platformCode, :endpointCode, :apiVersion, :httpMethod,
                            :pathTemplate, :capabilityId, :readWriteClass, :paginationModel,
                            :rateLimitPerMinute, :rateLimitNote, :quotaNote,
                            :idempotencySupport, :lateDataBehavior, :freshnessExpectation,
                            :businessKeyNote, :schemaVersion, :deprecatedAt,
                            :replacementEndpointId, :verificationState, :lastVerifiedAt,
                            :evidenceRef, :verifiedSourceTitle, :ownerLabel,
                            :contractTestStatus, :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", endpoint.id())
                .param("platformCode", endpoint.platformCode())
                .param("endpointCode", endpoint.endpointCode())
                .param("apiVersion", endpoint.apiVersion())
                .param("httpMethod", endpoint.httpMethod())
                .param("pathTemplate", endpoint.pathTemplate())
                .param("capabilityId", endpoint.capabilityId())
                .param("readWriteClass", endpoint.readWriteClass().name())
                .param("paginationModel", endpoint.paginationModel().name())
                .param("rateLimitPerMinute", endpoint.rateLimitPerMinute())
                .param("rateLimitNote", endpoint.rateLimitNote())
                .param("quotaNote", endpoint.quotaNote())
                .param("idempotencySupport", endpoint.idempotencySupport().name())
                .param("lateDataBehavior", endpoint.lateDataBehavior())
                .param("freshnessExpectation", endpoint.freshnessExpectation())
                .param("businessKeyNote", endpoint.businessKeyNote())
                .param("schemaVersion", endpoint.schemaVersion())
                .param("deprecatedAt", endpoint.deprecatedAt() == null
                        ? null : Timestamp.from(endpoint.deprecatedAt()))
                .param("replacementEndpointId", endpoint.replacementEndpointId())
                .param("verificationState", endpoint.verificationState().name())
                .param("lastVerifiedAt", endpoint.lastVerifiedAt() == null
                        ? null : Timestamp.from(endpoint.lastVerifiedAt()))
                .param("evidenceRef", endpoint.evidenceRef())
                .param("verifiedSourceTitle", endpoint.verifiedSourceTitle())
                .param("ownerLabel", endpoint.ownerLabel())
                .param("contractTestStatus", endpoint.contractTestStatus().name())
                .param("status", endpoint.status().name())
                .param("createdAt", Timestamp.from(endpoint.createdAt()))
                .param("updatedAt", Timestamp.from(endpoint.updatedAt()))
                .param("version", endpoint.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(PlatformEndpoint endpoint, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE platform.platform_endpoint
                        SET http_method = :httpMethod, path_template = :pathTemplate,
                            capability_id = :capabilityId,
                            read_write_class = :readWriteClass,
                            pagination_model = :paginationModel,
                            rate_limit_per_minute = :rateLimitPerMinute,
                            rate_limit_note = :rateLimitNote, quota_note = :quotaNote,
                            idempotency_support = :idempotencySupport,
                            late_data_behavior = :lateDataBehavior,
                            freshness_expectation = :freshnessExpectation,
                            business_key_note = :businessKeyNote,
                            schema_version = :schemaVersion, deprecated_at = :deprecatedAt,
                            replacement_endpoint_id = :replacementEndpointId,
                            verification_state = :verificationState,
                            last_verified_at = :lastVerifiedAt, evidence_ref = :evidenceRef,
                            verified_source_title = :verifiedSourceTitle,
                            owner_label = :ownerLabel,
                            contract_test_status = :contractTestStatus,
                            status = :status, updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("httpMethod", endpoint.httpMethod())
                .param("pathTemplate", endpoint.pathTemplate())
                .param("capabilityId", endpoint.capabilityId())
                .param("readWriteClass", endpoint.readWriteClass().name())
                .param("paginationModel", endpoint.paginationModel().name())
                .param("rateLimitPerMinute", endpoint.rateLimitPerMinute())
                .param("rateLimitNote", endpoint.rateLimitNote())
                .param("quotaNote", endpoint.quotaNote())
                .param("idempotencySupport", endpoint.idempotencySupport().name())
                .param("lateDataBehavior", endpoint.lateDataBehavior())
                .param("freshnessExpectation", endpoint.freshnessExpectation())
                .param("businessKeyNote", endpoint.businessKeyNote())
                .param("schemaVersion", endpoint.schemaVersion())
                .param("deprecatedAt", endpoint.deprecatedAt() == null
                        ? null : Timestamp.from(endpoint.deprecatedAt()))
                .param("replacementEndpointId", endpoint.replacementEndpointId())
                .param("verificationState", endpoint.verificationState().name())
                .param("lastVerifiedAt", endpoint.lastVerifiedAt() == null
                        ? null : Timestamp.from(endpoint.lastVerifiedAt()))
                .param("evidenceRef", endpoint.evidenceRef())
                .param("verifiedSourceTitle", endpoint.verifiedSourceTitle())
                .param("ownerLabel", endpoint.ownerLabel())
                .param("contractTestStatus", endpoint.contractTestStatus().name())
                .param("status", endpoint.status().name())
                .param("updatedAt", Timestamp.from(endpoint.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", endpoint.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one endpoint. */
    public Optional<PlatformEndpoint> findById(UUID id) {
        return jdbc.sql("SELECT * FROM platform.platform_endpoint WHERE id = :id")
                .param("id", id)
                .query(EndpointRepository::map)
                .optional();
    }

    /** Load one endpoint by platform, registry code and API version. */
    public Optional<PlatformEndpoint> findByCode(
            String platformCode, String endpointCode, String apiVersion) {
        return jdbc.sql("""
                        SELECT * FROM platform.platform_endpoint
                        WHERE platform_code = :platformCode AND endpoint_code = :endpointCode
                          AND api_version = :apiVersion
                        """)
                .param("platformCode", platformCode)
                .param("endpointCode", endpointCode)
                .param("apiVersion", apiVersion)
                .query(EndpointRepository::map)
                .optional();
    }

    /** List a platform's endpoints by code and version with a keyset cursor. */
    public List<PlatformEndpoint> list(
            String platformCode, String afterCode, String afterVersion, int limit) {
        return jdbc.sql("""
                        SELECT * FROM platform.platform_endpoint
                        WHERE platform_code = :platformCode
                          AND (CAST(:afterCode AS text) IS NULL
                              OR (endpoint_code, api_version) > (:afterCode, :afterVersion))
                        ORDER BY endpoint_code, api_version
                        LIMIT :pageLimit
                        """)
                .param("platformCode", platformCode)
                .param("afterCode", afterCode)
                .param("afterVersion", afterVersion)
                .param("pageLimit", limit)
                .query(EndpointRepository::map)
                .list();
    }

    /** Count non-retired endpoints serving one capability. */
    public long countActiveByCapability(UUID capabilityId) {
        return jdbc.sql("""
                        SELECT count(*) FROM platform.platform_endpoint
                        WHERE capability_id = :capabilityId AND status = 'ACTIVE'
                        """)
                .param("capabilityId", capabilityId)
                .query(Long.class)
                .single();
    }

    private static PlatformEndpoint map(ResultSet row, int rowNumber) throws SQLException {
        Timestamp deprecatedAt = row.getTimestamp("deprecated_at");
        Timestamp lastVerifiedAt = row.getTimestamp("last_verified_at");
        return new PlatformEndpoint(
                row.getObject("id", UUID.class),
                row.getString("platform_code"),
                row.getString("endpoint_code"),
                row.getString("api_version"),
                row.getString("http_method"),
                row.getString("path_template"),
                row.getObject("capability_id", UUID.class),
                ReadWriteClass.valueOf(row.getString("read_write_class")),
                PaginationModel.valueOf(row.getString("pagination_model")),
                row.getObject("rate_limit_per_minute", Integer.class),
                row.getString("rate_limit_note"),
                row.getString("quota_note"),
                TriState.valueOf(row.getString("idempotency_support")),
                row.getString("late_data_behavior"),
                row.getString("freshness_expectation"),
                row.getString("business_key_note"),
                row.getString("schema_version"),
                deprecatedAt == null ? null : deprecatedAt.toInstant(),
                row.getObject("replacement_endpoint_id", UUID.class),
                VerificationState.valueOf(row.getString("verification_state")),
                lastVerifiedAt == null ? null : lastVerifiedAt.toInstant(),
                row.getString("evidence_ref"),
                row.getString("verified_source_title"),
                row.getString("owner_label"),
                ContractTestStatus.valueOf(row.getString("contract_test_status")),
                RegistryStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}

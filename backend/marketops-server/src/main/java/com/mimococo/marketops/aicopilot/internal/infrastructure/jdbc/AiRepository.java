package com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc;

import com.mimococo.marketops.aicopilot.AiClaim;
import com.mimococo.marketops.aicopilot.AiClaimKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * The model provider registry, the invocation journal and the claims each
 * invocation produced.
 *
 * <p>Only an eligible provider is visible. Contractual and data-processing
 * eligibility for the operating business is external evidence, so a provider
 * nobody has verified simply does not appear here and the gateway has nothing to
 * call.
 *
 * <p>Claim evidence is stored as a foreign key to the canonical value or finding
 * it cites. A fabricated identifier therefore cannot be recorded at all: the
 * insert fails rather than producing a citation nobody can open.
 */
@Repository
public class AiRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    AiRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** The field paths a projection version is allowed to carry. */
    public Set<String> allowedProjectionFields(String projectionCode, int projectionVersion) {
        return Set.copyOf(jdbc.sql("""
                        SELECT field_path FROM ops.ai_projection_field
                         WHERE projection_code = :projectionCode
                           AND projection_version = :projectionVersion
                        """)
                .param("projectionCode", projectionCode)
                .param("projectionVersion", projectionVersion)
                .query(String.class)
                .list());
    }

    /**
     * The model this deployment should call, when an eligible one exists.
     *
     * <p>Eligibility is filtered in the database rather than in the caller, so a
     * provider whose contract has not been verified has no row a caller could
     * accidentally use.
     */
    public Optional<EligibleModel> eligibleModel() {
        return jdbc.sql("""
                        SELECT model.id, model.model_code, model.secret_reference,
                               model.max_context_tokens, provider.provider_code
                          FROM ops.ai_model AS model
                          JOIN ops.ai_provider AS provider ON provider.id = model.provider_id
                         WHERE model.status = 'ACTIVE'
                           AND provider.status = 'ACTIVE'
                           AND provider.eligibility_state = 'VERIFIED'
                         ORDER BY provider.provider_code, model.model_code
                         LIMIT 1
                        """)
                .query((rows, rowNumber) -> new EligibleModel(
                        rows.getObject("id", UUID.class),
                        rows.getString("provider_code"),
                        rows.getString("model_code"),
                        rows.getString("secret_reference"),
                        rows.getInt("max_context_tokens")))
                .optional();
    }

    /**
     * The recorded call shape of the provider offering one model.
     *
     * <p>Eligibility and completeness are filtered in the database, so a
     * provider whose contract or wire shape has not been recorded has no row a
     * caller could accidentally use.
     */
    public Optional<ProviderCallSpec> eligibleProviderSpec(String modelCode) {
        return jdbc.sql("""
                        SELECT provider.provider_code, provider.invocation_url, provider.request_template,
                               provider.response_pointer, provider.auth_header_name,
                               provider.auth_value_template, provider.request_timeout_ms
                          FROM ops.ai_model AS model
                          JOIN ops.ai_provider AS provider ON provider.id = model.provider_id
                         WHERE model.model_code = :modelCode
                           AND model.status = 'ACTIVE'
                           AND provider.status = 'ACTIVE'
                           AND provider.eligibility_state = 'VERIFIED'
                         LIMIT 1
                        """)
                .param("modelCode", modelCode)
                .query((rows, rowNumber) -> new ProviderCallSpec(
                        rows.getString("invocation_url"),
                        rows.getString("request_template"),
                        rows.getString("response_pointer"),
                        rows.getString("auth_header_name"),
                        rows.getString("auth_value_template"),
                        rows.getInt("request_timeout_ms"), rows.getString("provider_code")))
                .optional();
    }

    /** Record a model call before it is made. */
    public void openInvocation(UUID id, UUID organizationId, String projectionCode,
                               int projectionVersion, String promptTemplateCode,
                               int promptVersion, UUID modelId, String subjectKind,
                               UUID subjectId, String windowCode, String requestDigest,
                               String state, UUID requestedByUserId, Instant startedAt,
                               String correlationId) {
        jdbc.sql("""
                        INSERT INTO ops.ai_invocation (
                            id, organization_id, projection_code, projection_version,
                            prompt_template_code, prompt_version, model_id, subject_kind,
                            subject_id, window_code, request_digest, state, degraded,
                            requested_by_user_id, started_at, correlation_id)
                        VALUES (:id, :organizationId, :projectionCode, :projectionVersion,
                            :promptTemplateCode, :promptVersion, :modelId, :subjectKind,
                            :subjectId, :windowCode, :requestDigest, :state, false,
                            :requestedByUserId, :startedAt, :correlationId)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("projectionCode", projectionCode)
                .param("projectionVersion", projectionVersion)
                .param("promptTemplateCode", promptTemplateCode)
                .param("promptVersion", promptVersion)
                .param("modelId", modelId)
                .param("subjectKind", subjectKind)
                .param("subjectId", subjectId)
                .param("windowCode", windowCode)
                .param("requestDigest", requestDigest)
                .param("state", state)
                .param("requestedByUserId", requestedByUserId)
                .param("startedAt", Timestamp.from(startedAt))
                .param("correlationId", correlationId)
                .update();
    }

    /** Record how a model call ended. */
    public void closeInvocation(UUID id, String state, String failureCode, boolean degraded,
                                Integer latencyMillis, Instant completedAt) {
        int changed = jdbc.sql("""
                        UPDATE ops.ai_invocation
                        SET state = :state, failure_code = :failureCode, degraded = :degraded,
                            latency_ms = :latencyMillis, completed_at = :completedAt
                        WHERE id = :id AND state IN ('PREPARED','DISPATCHED')
                          AND (state='PREPARED' OR execution_deadline_at > clock_timestamp())
                        """)
                .param("state", state)
                .param("failureCode", failureCode)
                .param("degraded", degraded)
                .param("latencyMillis", latencyMillis)
                .param("completedAt", Timestamp.from(completedAt))
                .param("id", id)
                .update();
        if (changed != 1) throw com.mimococo.marketops.shared.OperationRejectedException.of(
                com.mimococo.marketops.shared.ErrorCode.VERSION_CONFLICT);
    }

    /** A dispatched call past its deadline is uncertain, and is never sent again. */
    public List<RecoveredInvocation> recoverExpired() {
        return jdbc.sql("""
                UPDATE ops.ai_invocation SET state='PROVIDER_OUTCOME_UNKNOWN',
                    failure_code='WORKER_INTERRUPTED_OR_DEADLINE_EXPIRED', degraded=true,
                    completed_at=clock_timestamp()
                 WHERE id IN (SELECT id FROM ops.ai_invocation WHERE state='DISPATCHED'
                    AND execution_deadline_at <= clock_timestamp()
                    ORDER BY execution_deadline_at LIMIT 100 FOR UPDATE SKIP LOCKED)
                 RETURNING id,requested_by_user_id
                """).query((row, index) -> new RecoveredInvocation(row.getObject("id", UUID.class),
                        row.getObject("requested_by_user_id", UUID.class))).list();
    }

    public record RecoveredInvocation(UUID id, UUID requestedBy) { }

    /** Record one claim and its citations. */
    public void recordClaim(UUID id, UUID invocationId, int ordinal, AiClaimKind kind,
                            String statement, Map<String, Object> payload,
                            String confidenceLabel, boolean accepted, String rejectionCode,
                            List<UUID> metricValueRefs, List<UUID> findingRefs,
                            java.util.function.Supplier<UUID> evidenceIdSupplier) {
        jdbc.sql("""
                        INSERT INTO ops.ai_output_claim (
                            id, invocation_id, ordinal, claim_kind, statement, payload,
                            confidence_label, validation_state, rejection_code)
                        VALUES (:id, :invocationId, :ordinal, :claimKind, :statement,
                            CAST(:payload AS jsonb), :confidenceLabel, :validationState,
                            :rejectionCode)
                        """)
                .param("id", id)
                .param("invocationId", invocationId)
                .param("ordinal", ordinal)
                .param("claimKind", kind.name())
                .param("statement", statement)
                .param("payload", objectMapper.writeValueAsString(payload))
                .param("confidenceLabel", confidenceLabel)
                .param("validationState", accepted ? "ACCEPTED" : "REJECTED")
                .param("rejectionCode", rejectionCode)
                .update();

        metricValueRefs.forEach(reference -> jdbc.sql("""
                        INSERT INTO ops.ai_claim_evidence (id, claim_id, metric_value_id)
                        VALUES (:id, :claimId, :metricValueId)
                        """)
                .param("id", evidenceIdSupplier.get())
                .param("claimId", id)
                .param("metricValueId", reference)
                .update());
        findingRefs.forEach(reference -> jdbc.sql("""
                        INSERT INTO ops.ai_claim_evidence (id, claim_id, finding_id)
                        VALUES (:id, :claimId, :findingId)
                        """)
                .param("id", evidenceIdSupplier.get())
                .param("claimId", id)
                .param("findingId", reference)
                .update());
    }

    /** One recorded invocation. */
    public Optional<InvocationRow> findInvocation(UUID id) {
        return jdbc.sql("""
                        SELECT invocation.id, invocation.subject_id, invocation.output_schema_version, invocation.state,
                               invocation.failure_code, invocation.degraded,
                               invocation.started_at, invocation.completed_at,
                               provider.provider_code, model.model_code
                          FROM ops.ai_invocation AS invocation
                          LEFT JOIN ops.ai_model AS model ON model.id = invocation.model_id
                          LEFT JOIN ops.ai_provider AS provider
                            ON provider.id = model.provider_id
                         WHERE invocation.id = :id
                        """)
                .param("id", id)
                .query(AiRepository::mapInvocation)
                .optional();
    }

    /** The claims of one invocation, in the order the model produced them. */
    public List<AiClaim> claimsOf(UUID invocationId) {
        List<AiClaim> claims = new ArrayList<>();
        jdbc.sql("""
                        SELECT id, ordinal, claim_kind, statement,
                               CAST(payload AS text) AS payload, confidence_label,
                               validation_state, rejection_code
                          FROM ops.ai_output_claim
                         WHERE invocation_id = :invocationId
                         ORDER BY claim_kind, ordinal
                        """)
                .param("invocationId", invocationId)
                .query((rows, rowNumber) -> claims.add(mapClaim(rows)))
                .list();
        return List.copyOf(claims);
    }

    private AiClaim mapClaim(ResultSet rows) throws SQLException {
        UUID claimId = rows.getObject("id", UUID.class);
        Map<String, Object> payload = com.mimococo.marketops.shared.JsonValues.object(
                com.mimococo.marketops.shared.JsonValues.read(objectMapper, rows.getString("payload")));
        return new AiClaim(
                claimId,
                AiClaimKind.valueOf(rows.getString("claim_kind")),
                rows.getInt("ordinal"),
                rows.getString("statement"),
                rows.getString("confidence_label"),
                citations(claimId, "metric_value_id"),
                citations(claimId, "finding_id"),
                Map.copyOf(payload),
                "ACCEPTED".equals(rows.getString("validation_state")),
                rows.getString("rejection_code"));
    }

    private List<UUID> citations(UUID claimId, String column) {
        return jdbc.sql("SELECT " + column + " FROM ops.ai_claim_evidence"
                        + " WHERE claim_id = :claimId AND " + column + " IS NOT NULL"
                        + " ORDER BY " + column)
                .param("claimId", claimId)
                .query(UUID.class)
                .list();
    }

    private static InvocationRow mapInvocation(ResultSet rows, int rowNumber)
            throws SQLException {
        Timestamp completedAt = rows.getTimestamp("completed_at");
        return new InvocationRow(
                rows.getObject("id", UUID.class),
                rows.getObject("subject_id", UUID.class),
                rows.getString("state"),
                rows.getString("failure_code"),
                rows.getBoolean("degraded"),
                rows.getString("provider_code"),
                rows.getString("model_code"),
                rows.getTimestamp("started_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(), rows.getInt("output_schema_version"));
    }

    /**
     * The recorded call shape of one provider.
     *
     * @param invocationUrl where the call is sent
     * @param requestTemplate the request document, with placeholders
     * @param responsePointer where the answer lives inside the response
     * @param authHeaderName the header the provider expects
     * @param authValueTemplate the header value shape, with one placeholder
     * @param requestTimeoutMillis how long one call may take
     */
    public record ProviderCallSpec(
            String invocationUrl, String requestTemplate, String responsePointer,
            String authHeaderName, String authValueTemplate, int requestTimeoutMillis, String providerCode) {
    }

    /**
     * A model this deployment may call.
     *
     * @param modelId identifier of the model registration
     * @param providerCode the provider's business code
     * @param modelCode the provider's own name for the model
     * @param secretReference opaque reference to the credential
     * @param maximumContextTokens recorded context ceiling, or zero when unrecorded
     */
    public record EligibleModel(
            UUID modelId, String providerCode, String modelCode, String secretReference,
            int maximumContextTokens) {
    }

    /**
     * One recorded invocation.
     *
     * @param id identifier
     * @param subjectId the subject the model was asked about
     * @param state how the invocation ended
     * @param failureCode why it did not succeed, or {@code null}
     * @param degraded whether the caller should present this as unavailable
     * @param providerCode which provider answered, or {@code null}
     * @param modelCode which model answered, or {@code null}
     * @param startedAt when the invocation began
     * @param completedAt when it ended, or {@code null}
     */
    public record InvocationRow(
            UUID id, UUID subjectId, String state, String failureCode, boolean degraded,
            String providerCode, String modelCode, Instant startedAt, Instant completedAt, int outputSchemaVersion) {
    }
}

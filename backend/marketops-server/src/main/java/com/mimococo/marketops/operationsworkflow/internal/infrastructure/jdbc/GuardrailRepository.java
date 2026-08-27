package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * The append-only record of every guardrail verdict.
 *
 * <p>Passes are recorded as well as blocks. A journal that held only refusals
 * could not answer the question that matters after an unwanted price change:
 * what did the system believe at the moment it decided this was acceptable.
 */
@Repository
public class GuardrailRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    GuardrailRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Record one verdict. */
    public void insert(UUID id, UUID organizationId, UUID recommendationId, UUID policyId,
                       Integer policyVersion, GuardrailPurpose purpose, boolean passed,
                       List<GuardrailReason> reasons, Map<String, String> detail,
                       String inputDigest, Instant evaluatedAt, String correlationId) {
        jdbc.sql("""
                        INSERT INTO ops.guardrail_evaluation (
                            id, organization_id, recommendation_id, policy_id, policy_version,
                            purpose, outcome, reason_codes, detail, input_digest, evaluated_at,
                            correlation_id)
                        VALUES (:id, :organizationId, :recommendationId, :policyId,
                            :policyVersion, :purpose, :outcome, :reasonCodes,
                            CAST(:detail AS jsonb), :inputDigest, :evaluatedAt,
                            :correlationId)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("recommendationId", recommendationId)
                .param("policyId", policyId)
                .param("policyVersion", policyVersion)
                .param("purpose", purpose.name())
                .param("outcome", passed ? "PASS" : "BLOCK")
                .param("reasonCodes", reasons.stream().map(Enum::name).toArray(String[]::new))
                .param("detail", objectMapper.writeValueAsString(detail))
                .param("inputDigest", inputDigest)
                .param("evaluatedAt", Timestamp.from(evaluatedAt))
                .param("correlationId", correlationId)
                .update();
    }

    /** Every verdict about one proposal, newest first. */
    public List<EvaluationRow> history(UUID recommendationId, int limit) {
        return jdbc.sql("""
                        SELECT id, policy_id, policy_version, purpose, outcome, reason_codes,
                               input_digest, evaluated_at
                          FROM ops.guardrail_evaluation
                         WHERE recommendation_id = :recommendationId
                         ORDER BY evaluated_at DESC
                         LIMIT :limit
                        """)
                .param("recommendationId", recommendationId)
                .param("limit", limit)
                .query(GuardrailRepository::map)
                .list();
    }

    /** Whether an execution verdict for this proposal currently passes. */
    public boolean executionPassRecorded(UUID recommendationId) {
        return !jdbc.sql("""
                        SELECT id FROM ops.guardrail_evaluation
                         WHERE recommendation_id = :recommendationId
                           AND purpose = 'EXECUTION' AND outcome = 'PASS'
                         LIMIT 1
                        """)
                .param("recommendationId", recommendationId)
                .query(UUID.class)
                .list()
                .isEmpty();
    }

    private static EvaluationRow map(ResultSet rows, int rowNumber) throws SQLException {
        Object reasonArray = rows.getArray("reason_codes").getArray();
        List<String> reasons = reasonArray instanceof String[] codes
                ? List.of(codes) : Arrays.stream((Object[]) reasonArray)
                        .map(String::valueOf).toList();
        Integer policyVersion = rows.getInt("policy_version");
        if (rows.wasNull()) {
            policyVersion = null;
        }
        return new EvaluationRow(
                rows.getObject("id", UUID.class),
                rows.getObject("policy_id", UUID.class),
                policyVersion,
                GuardrailPurpose.valueOf(rows.getString("purpose")),
                "PASS".equals(rows.getString("outcome")),
                reasons,
                rows.getString("input_digest"),
                rows.getTimestamp("evaluated_at").toInstant());
    }

    /**
     * One recorded verdict.
     *
     * @param id the evaluation
     * @param policyId policy it was decided under, or {@code null}
     * @param policyVersion version of that policy, or {@code null}
     * @param purpose why it ran
     * @param passed whether the action was permitted
     * @param reasonCodes every blocking condition
     * @param inputDigest digest of the inputs
     * @param evaluatedAt when it ran
     */
    public record EvaluationRow(UUID id, UUID policyId, Integer policyVersion,
                                GuardrailPurpose purpose, boolean passed,
                                List<String> reasonCodes, String inputDigest,
                                Instant evaluatedAt) {
    }
}

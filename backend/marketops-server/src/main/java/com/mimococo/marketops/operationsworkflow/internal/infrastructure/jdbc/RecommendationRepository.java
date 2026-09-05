package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Proposals and the evidence they rest on.
 *
 * <p>A state change is a versioned update that names both the state it expects
 * to be leaving and the version it read. Two operators acting on the same
 * proposal at once produce one change and one refusal rather than a last-write
 * winner, which matters because the losing write here would be an approval.
 *
 * <p>Evidence is append-only and its identifiers are foreign keys, so a
 * reference to a value that does not exist cannot be stored at all.
 */
@Repository
public class RecommendationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    RecommendationRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Exact candidate reuse never returns an unrelated cause on the same object. */
    public Optional<UUID> liveForAdvertisingCandidate(UUID organizationId, UUID candidateId) {
        return jdbc.sql("""
                SELECT id FROM ops.recommendation WHERE organization_id=:org
                  AND action_kind='AD_BID_CHANGE' AND proposed_parameters->>'candidateId'=:candidate
                  AND state NOT IN ('REJECTED','EXPIRED','CANCELLED','CLOSED')
                ORDER BY created_at DESC,id LIMIT 1
                """).param("org", organizationId).param("candidate", candidateId.toString())
                .query(UUID.class).optional();
    }

    /** Propose an action. */
    public void insert(UUID id, UUID organizationId, UUID storeId, SubjectKind subjectKind,
                       UUID subjectId, ActionKind actionKind, String origin,
                       UUID aiInvocationId, UUID calculationRunId, MetricWindow window,
                       RecommendationState state, BigDecimal priorityScore,
                       Map<String, String> proposedParameters,
                       Map<String, String> expectedEffect, String riskLabel,
                       int validationHorizonDays, String entityVersionDigest,
                       Instant validUntil, Instant now) {
        jdbc.sql("""
                        INSERT INTO ops.recommendation (
                            id, organization_id, store_id, subject_kind, subject_id,
                            action_kind, origin, ai_invocation_id, calculation_run_id,
                            window_code, state, priority_score, proposed_parameters,
                            expected_effect, risk_label, validation_horizon_days,
                            entity_version_digest, valid_until, created_at, updated_at,
                            version)
                        VALUES (:id, :organizationId, :storeId, :subjectKind, :subjectId,
                            :actionKind, :origin, :aiInvocationId, :calculationRunId,
                            :windowCode, :state, :priorityScore,
                            CAST(:proposedParameters AS jsonb), CAST(:expectedEffect AS jsonb),
                            :riskLabel, :validationHorizonDays, :entityVersionDigest,
                            :validUntil, :now, :now, 0)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("actionKind", actionKind.name())
                .param("origin", origin)
                .param("aiInvocationId", aiInvocationId)
                .param("calculationRunId", calculationRunId)
                .param("windowCode", window.name())
                .param("state", state.name())
                .param("priorityScore", priorityScore)
                .param("proposedParameters", objectMapper.writeValueAsString(proposedParameters))
                .param("expectedEffect", objectMapper.writeValueAsString(expectedEffect))
                .param("riskLabel", riskLabel)
                .param("validationHorizonDays", validationHorizonDays)
                .param("entityVersionDigest", entityVersionDigest)
                .param("validUntil", Timestamp.from(validUntil))
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Link one thing the case rests on. */
    public void insertEvidence(UUID id, UUID recommendationId, UUID metricValueId,
                               UUID findingId, UUID aiClaimId, String role) {
        jdbc.sql("""
                        INSERT INTO ops.recommendation_evidence (
                            id, recommendation_id, metric_value_id, finding_id, ai_claim_id,
                            role)
                        VALUES (:id, :recommendationId, :metricValueId, :findingId, :aiClaimId,
                            :role)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", id)
                .param("recommendationId", recommendationId)
                .param("metricValueId", metricValueId)
                .param("findingId", findingId)
                .param("aiClaimId", aiClaimId)
                .param("role", role)
                .update();
    }

    /**
     * Move a proposal to a new state, or report that it moved underneath.
     *
     * <p>Both the state and the version are in the predicate. The version alone
     * would allow a transition from a state the caller never saw, and the state
     * alone would allow two callers to make the same transition twice.
     */
    public boolean transition(UUID id, RecommendationState from, RecommendationState to,
                              String terminalReason, Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.recommendation
                        SET state = :to, terminal_reason = :terminalReason, updated_at = :at,
                            version = :newVersion
                        WHERE id = :id AND state = :from AND version = :expectedVersion
                        """)
                .param("to", to.name())
                .param("terminalReason", terminalReason)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("from", from.name())
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /**
     * Expire every proposal whose validity window has elapsed.
     *
     * <p>Expiry is a recorded transition rather than a filter applied at read
     * time, so an operator sees that a proposal ended and why, and the write
     * gate has one definition of current to check against.
     */
    public int expireElapsed(Instant at) {
        return jdbc.sql("""
                        UPDATE ops.recommendation
                        SET state = 'EXPIRED', terminal_reason = 'VALIDITY_WINDOW_ELAPSED',
                            updated_at = :at, version = version + 1
                        WHERE valid_until <= :at
                          AND state IN ('DRAFT', 'VALIDATED', 'READY_FOR_REVIEW',
                                        'APPROVED', 'POLICY_AUTHORIZED')
                        """)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** One proposal with its evidence. */
    public Optional<RecommendationView> find(UUID id) {
        return jdbc.sql(SELECT_RECOMMENDATION + " WHERE recommendation.id = :id")
                .param("id", id)
                .query(this::map)
                .optional()
                .map(this::withEvidence);
    }

    /** The proposals of one store awaiting attention, most urgent first. */
    public List<RecommendationView> queue(UUID storeId, List<RecommendationState> states,
                                          int limit) {
        return queue(storeId, null, states, limit);
    }

    /** Apply subject scope before the bounded page, so other subjects cannot displace it. */
    public List<RecommendationView> queue(UUID storeId, UUID subjectId,
                                          List<RecommendationState> states, int limit) {
        List<RecommendationView> rows = jdbc.sql(SELECT_RECOMMENDATION + """
                         WHERE recommendation.store_id = :storeId
                           AND (CAST(:subjectId AS uuid) IS NULL OR recommendation.subject_id = :subjectId)
                           AND recommendation.state = ANY (:states)
                         ORDER BY recommendation.priority_score DESC,
                                  recommendation.valid_until
                         LIMIT :limit
                        """)
                .param("storeId", storeId)
                .param("subjectId", subjectId, java.sql.Types.OTHER)
                .param("states", states.stream().map(Enum::name).toArray(String[]::new))
                .param("limit", Math.clamp(limit, 1, 200))
                .query(this::map)
                .list();
        return rows.stream().map(this::withEvidence).toList();
    }

    private RecommendationView withEvidence(RecommendationView proposal) {
        List<RecommendationView.EvidenceRef> evidence = jdbc.sql("""
                        SELECT metric_value_id, finding_id, ai_claim_id, role
                          FROM ops.recommendation_evidence
                         WHERE recommendation_id = :recommendationId
                         ORDER BY role
                        """)
                .param("recommendationId", proposal.id())
                .query((rows, rowNumber) -> new RecommendationView.EvidenceRef(
                        rows.getObject("metric_value_id", UUID.class),
                        rows.getObject("finding_id", UUID.class),
                        rows.getObject("ai_claim_id", UUID.class),
                        rows.getString("role")))
                .list();
        return new RecommendationView(proposal.id(), proposal.organizationId(),
                proposal.storeId(), proposal.subjectKind(), proposal.subjectId(),
                proposal.actionKind(), proposal.origin(), proposal.aiInvocationId(),
                proposal.window(), proposal.state(), proposal.priorityScore(),
                proposal.proposedParameters(), proposal.expectedEffect(), proposal.riskLabel(),
                proposal.validationHorizonDays(), proposal.entityVersionDigest(),
                proposal.validUntil(), proposal.terminalReason(), evidence,
                proposal.createdAt(), proposal.version());
    }

    private static final String SELECT_RECOMMENDATION = """
            SELECT recommendation.id, recommendation.organization_id, recommendation.store_id,
                   recommendation.subject_kind, recommendation.subject_id,
                   recommendation.action_kind, recommendation.origin,
                   recommendation.ai_invocation_id, recommendation.window_code,
                   recommendation.state, recommendation.priority_score,
                   recommendation.proposed_parameters, recommendation.expected_effect,
                   recommendation.risk_label, recommendation.validation_horizon_days,
                   recommendation.entity_version_digest, recommendation.valid_until,
                   recommendation.terminal_reason, recommendation.created_at,
                   recommendation.version
              FROM ops.recommendation AS recommendation
            """;

    private RecommendationView map(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp terminal = rows.getTimestamp("valid_until");
        return new RecommendationView(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                SubjectKind.valueOf(rows.getString("subject_kind")),
                rows.getObject("subject_id", UUID.class),
                ActionKind.valueOf(rows.getString("action_kind")),
                rows.getString("origin"),
                rows.getObject("ai_invocation_id", UUID.class),
                MetricWindow.valueOf(rows.getString("window_code")),
                RecommendationState.valueOf(rows.getString("state")),
                rows.getBigDecimal("priority_score"),
                readMap(rows.getString("proposed_parameters")),
                readMap(rows.getString("expected_effect")),
                rows.getString("risk_label"),
                rows.getInt("validation_horizon_days"),
                rows.getString("entity_version_digest"),
                terminal.toInstant(),
                rows.getString("terminal_reason"),
                List.of(),
                rows.getTimestamp("created_at").toInstant(),
                rows.getLong("version"));
    }

    /**
     * Read a stored parameter document back as text pairs.
     *
     * <p>Values are rendered as text on the way in and read as text on the way
     * out, so a number that a serializer would have widened or narrowed comes
     * back exactly as it was written.
     */
    private Map<String, String> readMap(String document) {
        if (document == null || document.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        objectMapper.readTree(document).properties()
                .forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
        return Map.copyOf(values);
    }

    /** Legacy diagnostic counts exclude advertising; its complete-scope queue owns those counts. */
    public Map<RecommendationState, Integer> stateCounts(UUID storeId) {
        Map<RecommendationState, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT state, count(*) AS total
                          FROM ops.recommendation
                         WHERE store_id = :storeId AND subject_kind <> 'AD_NATIVE_OBJECT'
                         GROUP BY state
                        """)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> {
                    counts.put(RecommendationState.valueOf(rows.getString("state")),
                            rows.getInt("total"));
                    return rows.getString("state");
                })
                .list();
        return Map.copyOf(counts);
    }

    /** Every live proposal for one subject and action, for duplicate detection. */
    public List<UUID> liveFor(SubjectKind subjectKind, UUID subjectId, ActionKind actionKind) {
        List<UUID> found = new ArrayList<>(jdbc.sql("""
                        SELECT id FROM ops.recommendation
                         WHERE subject_kind = :subjectKind AND subject_id = :subjectId
                           AND action_kind = :actionKind
                           AND state IN ('DRAFT', 'VALIDATED', 'READY_FOR_REVIEW', 'TASK_ONLY',
                                         'APPROVED', 'POLICY_AUTHORIZED', 'COMMAND_CREATED',
                                         'EXECUTION_TRACKING', 'OUTCOME_OBSERVATION')
                        """)
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("actionKind", actionKind.name())
                .query(UUID.class)
                .list());
        return List.copyOf(found);
    }
}

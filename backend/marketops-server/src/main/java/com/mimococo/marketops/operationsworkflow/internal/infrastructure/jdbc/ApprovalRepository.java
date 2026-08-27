package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

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
 * The permanent record of who authorized what.
 *
 * <p>Append-only, with a partial unique index the database enforces: one
 * recommendation can carry at most one standing authorization. A second
 * approval of the same proposal would be a second licence to write, and the
 * refusal happens at insertion rather than in a check a caller might skip.
 *
 * <p>The digest of the facts is stored with the decision. That is what makes an
 * approval specific to what was reviewed: if the facts move, the write gate
 * compares the stored digest against the current one and refuses.
 */
@Repository
public class ApprovalRepository {

    private final JdbcClient jdbc;

    ApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Record a decision. */
    public void insert(UUID id, UUID organizationId, UUID recommendationId, String decision,
                       UUID decidedByUserId, UUID policyAuthorizationId,
                       Instant authenticatedAt, boolean stepUpSatisfied,
                       String entityVersionDigest, Instant scopeExpiresAt, String reason,
                       Instant decidedAt, String correlationId) {
        jdbc.sql("""
                        INSERT INTO ops.approval_decision (
                            id, organization_id, recommendation_id, decision,
                            decided_by_user_id, policy_authorization_id, authenticated_at,
                            step_up_satisfied, entity_version_digest, scope_expires_at, reason,
                            decided_at, correlation_id)
                        VALUES (:id, :organizationId, :recommendationId, :decision,
                            :decidedByUserId, :policyAuthorizationId, :authenticatedAt,
                            :stepUpSatisfied, :entityVersionDigest, :scopeExpiresAt, :reason,
                            :decidedAt, :correlationId)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("recommendationId", recommendationId)
                .param("decision", decision)
                .param("decidedByUserId", decidedByUserId)
                .param("policyAuthorizationId", policyAuthorizationId)
                .param("authenticatedAt",
                        authenticatedAt == null ? null : Timestamp.from(authenticatedAt))
                .param("stepUpSatisfied", stepUpSatisfied)
                .param("entityVersionDigest", entityVersionDigest)
                .param("scopeExpiresAt", Timestamp.from(scopeExpiresAt))
                .param("reason", reason)
                .param("decidedAt", Timestamp.from(decidedAt))
                .param("correlationId", correlationId)
                .update();
    }

    /** The standing authorization of one proposal, when it has one. */
    public Optional<DecisionRow> standingAuthorization(UUID recommendationId) {
        return jdbc.sql(SELECT_DECISION + """
                         WHERE recommendation_id = :recommendationId
                           AND decision IN ('APPROVED', 'POLICY_AUTHORIZED')
                        """)
                .param("recommendationId", recommendationId)
                .query(ApprovalRepository::map)
                .optional();
    }

    /** Every decision about one proposal, newest first. */
    public List<DecisionRow> history(UUID recommendationId) {
        return jdbc.sql(SELECT_DECISION + """
                         WHERE recommendation_id = :recommendationId
                         ORDER BY decided_at DESC
                        """)
                .param("recommendationId", recommendationId)
                .query(ApprovalRepository::map)
                .list();
    }

    private static final String SELECT_DECISION = """
            SELECT id, recommendation_id, decision, decided_by_user_id,
                   policy_authorization_id, authenticated_at, step_up_satisfied,
                   entity_version_digest, scope_expires_at, reason, decided_at
              FROM ops.approval_decision
            """;

    private static DecisionRow map(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp authenticatedAt = rows.getTimestamp("authenticated_at");
        return new DecisionRow(
                rows.getObject("id", UUID.class),
                rows.getObject("recommendation_id", UUID.class),
                rows.getString("decision"),
                rows.getObject("decided_by_user_id", UUID.class),
                rows.getObject("policy_authorization_id", UUID.class),
                authenticatedAt == null ? null : authenticatedAt.toInstant(),
                rows.getBoolean("step_up_satisfied"),
                rows.getString("entity_version_digest"),
                rows.getTimestamp("scope_expires_at").toInstant(),
                rows.getString("reason"),
                rows.getTimestamp("decided_at").toInstant());
    }

    /**
     * One recorded decision.
     *
     * @param id the decision
     * @param recommendationId what it decided about
     * @param decision what was decided
     * @param decidedByUserId the person, or {@code null} for a standing rule
     * @param policyAuthorizationId the authorization spent, or {@code null}
     * @param authenticatedAt when the person authenticated, or {@code null}
     * @param stepUpSatisfied whether a recent authentication was proven
     * @param entityVersionDigest identity of the facts as reviewed
     * @param scopeExpiresAt when the authorization stops covering a write
     * @param reason why it was decided
     * @param decidedAt when it was decided
     */
    public record DecisionRow(UUID id, UUID recommendationId, String decision,
                              UUID decidedByUserId, UUID policyAuthorizationId,
                              Instant authenticatedAt, boolean stepUpSatisfied,
                              String entityVersionDigest, Instant scopeExpiresAt, String reason,
                              Instant decidedAt) {
    }
}

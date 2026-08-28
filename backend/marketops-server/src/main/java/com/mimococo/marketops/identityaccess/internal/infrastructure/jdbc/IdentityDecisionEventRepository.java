package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only journal of identity boundary decisions.
 *
 * <p>The application role holds INSERT and SELECT and nothing else, so a
 * recorded denial cannot be edited afterwards. Nothing replayable is written:
 * the subject and session appear only as digests, and no claim payload is
 * stored.
 */
@Repository
public class IdentityDecisionEventRepository {

    private final JdbcClient jdbc;

    IdentityDecisionEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Append one decision. */
    public void append(UUID id,
                       UUID identityProviderId,
                       String issuer,
                       String subjectDigest,
                       String sessionDigest,
                       UUID userId,
                       String decision,
                       String denialCode,
                       String actionCode,
                       String resourceType,
                       UUID resourceId,
                       Instant authenticatedAt,
                       boolean multiFactorPresent,
                       String correlationId) {
        jdbc.sql("""
                        INSERT INTO iam.identity_decision_event (
                            id, identity_provider_id, issuer, subject_digest, session_digest,
                            user_id, decision, denial_code, action_code, resource_type,
                            resource_id, authenticated_at, multi_factor_present, correlation_id)
                        VALUES (:id, :providerId, :issuer, :subjectDigest, :sessionDigest,
                            :userId, :decision, :denialCode, :actionCode, :resourceType,
                            :resourceId, :authenticatedAt, :multiFactor, :correlationId)
                        """)
                .param("id", id)
                .param("providerId", identityProviderId)
                .param("issuer", issuer)
                .param("subjectDigest", subjectDigest)
                .param("sessionDigest", sessionDigest)
                .param("userId", userId)
                .param("decision", decision)
                .param("denialCode", denialCode)
                .param("actionCode", actionCode)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("authenticatedAt",
                        authenticatedAt == null ? null : Timestamp.from(authenticatedAt))
                .param("multiFactor", multiFactorPresent)
                .param("correlationId", correlationId)
                .update();
    }
}

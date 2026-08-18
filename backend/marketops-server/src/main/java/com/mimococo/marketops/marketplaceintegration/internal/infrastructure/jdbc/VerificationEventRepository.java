package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only access to {@code platform.capability_verification_event}.
 *
 * <p>The journal records every verification and availability transition with
 * its evidence. Rows are inserted and read; {@code occurred_at} is stamped by
 * the database and never supplied, and no update statement exists here — the
 * application role holds no UPDATE grant on this table.
 */
@Repository
public class VerificationEventRepository {

    private final JdbcClient jdbc;

    VerificationEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Append one transition; the database stamps the insertion time. */
    public void insert(VerificationEvent event) {
        jdbc.sql("""
                        INSERT INTO platform.capability_verification_event (
                            id, capability_id, endpoint_id, capability_subject_status_id,
                            platform_permission_requirement_id, from_state, to_state,
                            evidence_ref, source_title, verified_at, actor, reason,
                            correlation_id)
                        VALUES (:id, :capabilityId, :endpointId, :capabilitySubjectStatusId,
                            :platformPermissionRequirementId, :fromState, :toState,
                            :evidenceRef, :sourceTitle, :verifiedAt, :actor, :reason,
                            :correlationId)
                        """)
                .param("id", event.id())
                .param("capabilityId", event.capabilityId())
                .param("endpointId", event.endpointId())
                .param("capabilitySubjectStatusId", event.capabilitySubjectStatusId())
                .param("platformPermissionRequirementId",
                        event.platformPermissionRequirementId())
                .param("fromState", event.fromState())
                .param("toState", event.toState())
                .param("evidenceRef", event.evidenceRef())
                .param("sourceTitle", event.sourceTitle())
                .param("verifiedAt", event.verifiedAt() == null
                        ? null : Timestamp.from(event.verifiedAt()))
                .param("actor", event.actor())
                .param("reason", event.reason())
                .param("correlationId", event.correlationId())
                .update();
    }

    /** List one capability's transitions, newest first. */
    public List<VerificationEvent> listByCapability(UUID capabilityId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM platform.capability_verification_event
                        WHERE capability_id = :capabilityId
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT :pageLimit
                        """)
                .param("capabilityId", capabilityId)
                .param("pageLimit", limit)
                .query(VerificationEventRepository::map)
                .list();
    }

    /** List one endpoint's transitions, newest first. */
    public List<VerificationEvent> listByEndpoint(UUID endpointId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM platform.capability_verification_event
                        WHERE endpoint_id = :endpointId
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT :pageLimit
                        """)
                .param("endpointId", endpointId)
                .param("pageLimit", limit)
                .query(VerificationEventRepository::map)
                .list();
    }

    private static VerificationEvent map(ResultSet row, int rowNumber) throws SQLException {
        Timestamp verifiedAt = row.getTimestamp("verified_at");
        return new VerificationEvent(
                row.getObject("id", UUID.class),
                row.getObject("capability_id", UUID.class),
                row.getObject("endpoint_id", UUID.class),
                row.getObject("capability_subject_status_id", UUID.class),
                row.getObject("platform_permission_requirement_id", UUID.class),
                row.getString("from_state"),
                row.getString("to_state"),
                row.getString("evidence_ref"),
                row.getString("source_title"),
                verifiedAt == null ? null : verifiedAt.toInstant(),
                row.getString("actor"),
                row.getString("reason"),
                row.getTimestamp("occurred_at").toInstant(),
                row.getString("correlation_id"));
    }
}

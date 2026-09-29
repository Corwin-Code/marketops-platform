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

/** A store's standing authorization for scheduled collection, and what the scheduler did under it. */
@Repository
public class ScheduledCollectionRepository {

    private static final String POLICY_COLUMNS = """
            SELECT id, organization_id, store_id, authorized_by_user_id, authorized_at, reason, version
              FROM ops.scheduled_collection_policy
            """;

    private static final String EVENT_COLUMNS = """
            SELECT id, organization_id, store_id, policy_id, job_id, dataset_kind, event_kind, target_key,
                   detail::text AS detail, ingestion_run_id, calculation_run_id, occurred_at
              FROM ops.scheduled_collection_event
            """;

    private final JdbcClient jdbc;

    ScheduledCollectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Record a new policy in force. */
    public void insertPolicy(Policy policy) {
        jdbc.sql("""
                        INSERT INTO ops.scheduled_collection_policy (
                            id, organization_id, store_id, status, authorized_by_user_id, authorized_at, reason,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :storeId, 'ACTIVE', :authorizedBy, :authorizedAt, :reason,
                            :authorizedAt, :authorizedAt, 0)
                        """)
                .param("id", policy.id())
                .param("organizationId", policy.organizationId())
                .param("storeId", policy.storeId())
                .param("authorizedBy", policy.authorizedByUserId())
                .param("authorizedAt", Timestamp.from(policy.authorizedAt()))
                .param("reason", policy.reason())
                .update();
    }

    /** The store's policy in force. */
    public Optional<Policy> findActivePolicy(UUID organizationId, UUID storeId) {
        return jdbc.sql(POLICY_COLUMNS + """
                         WHERE organization_id = :organizationId AND store_id = :storeId AND status = 'ACTIVE'
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query(ScheduledCollectionRepository::mapPolicy)
                .optional();
    }

    /** Every policy in force, oldest first. */
    public List<Policy> activePolicies() {
        return jdbc.sql(POLICY_COLUMNS + " WHERE status = 'ACTIVE' ORDER BY authorized_at, id")
                .query(ScheduledCollectionRepository::mapPolicy)
                .list();
    }

    /** Take a policy out of force; false when it changed or was retired meanwhile. */
    public boolean retirePolicy(UUID id, UUID retiredBy, Instant at, String reason, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.scheduled_collection_policy
                           SET status = 'RETIRED', retired_by_user_id = :retiredBy, retired_at = :at,
                               retirement_reason = :reason, updated_at = :at, version = version + 1
                         WHERE id = :id AND status = 'ACTIVE' AND version = :expectedVersion
                        """)
                .param("id", id)
                .param("retiredBy", retiredBy)
                .param("at", Timestamp.from(at))
                .param("reason", reason)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Add one record of what the scheduler did. */
    public void insertEvent(Event event) {
        jdbc.sql("""
                        INSERT INTO ops.scheduled_collection_event (
                            id, organization_id, store_id, policy_id, job_id, dataset_kind, event_kind, target_key,
                            detail, ingestion_run_id, calculation_run_id, occurred_at)
                        VALUES (:id, :organizationId, :storeId, :policyId, :jobId, :datasetKind, :kind, :targetKey,
                            CAST(:detail AS jsonb), :ingestionRunId, :calculationRunId, :occurredAt)
                        """)
                .param("id", event.id())
                .param("organizationId", event.organizationId())
                .param("storeId", event.storeId())
                .param("policyId", event.policyId())
                .param("jobId", event.jobId())
                .param("datasetKind", event.datasetKind())
                .param("kind", event.kind())
                .param("targetKey", event.targetKey())
                .param("detail", event.detail())
                .param("ingestionRunId", event.ingestionRunId())
                .param("calculationRunId", event.calculationRunId())
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .update();
    }

    /** The newest record about one job. */
    public Optional<Event> latestJobEvent(UUID jobId) {
        return jdbc.sql(EVENT_COLUMNS + " WHERE job_id = :jobId ORDER BY occurred_at DESC, id DESC LIMIT 1")
                .param("jobId", jobId)
                .query(ScheduledCollectionRepository::mapEvent)
                .optional();
    }

    /** The newest record of one kind about a store and target, e.g. a failed recalculation of D7. */
    public Optional<Event> latestStoreEvent(UUID storeId, String kind, String targetKey) {
        return jdbc.sql(EVENT_COLUMNS + """
                         WHERE store_id = :storeId AND event_kind = :kind AND target_key = :targetKey
                         ORDER BY occurred_at DESC, id DESC LIMIT 1
                        """)
                .param("storeId", storeId)
                .param("kind", kind)
                .param("targetKey", targetKey)
                .query(ScheduledCollectionRepository::mapEvent)
                .optional();
    }

    /** The store's newest records, newest first. */
    public List<Event> recentEvents(UUID storeId, int limit) {
        return jdbc.sql(EVENT_COLUMNS + " WHERE store_id = :storeId ORDER BY occurred_at DESC, id DESC LIMIT :limit")
                .param("storeId", storeId)
                .param("limit", limit)
                .query(ScheduledCollectionRepository::mapEvent)
                .list();
    }

    private static Policy mapPolicy(ResultSet rows, int rowNumber) throws SQLException {
        return new Policy(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getObject("authorized_by_user_id", UUID.class),
                rows.getTimestamp("authorized_at").toInstant(),
                rows.getString("reason"),
                rows.getLong("version"));
    }

    private static Event mapEvent(ResultSet rows, int rowNumber) throws SQLException {
        return new Event(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getObject("policy_id", UUID.class),
                rows.getObject("job_id", UUID.class),
                rows.getString("dataset_kind"),
                rows.getString("event_kind"),
                rows.getString("target_key"),
                rows.getString("detail"),
                rows.getObject("ingestion_run_id", UUID.class),
                rows.getObject("calculation_run_id", UUID.class),
                rows.getTimestamp("occurred_at").toInstant());
    }

    /** One policy in force. */
    public record Policy(UUID id, UUID organizationId, UUID storeId, UUID authorizedByUserId,
                         Instant authorizedAt, String reason, long version) {
    }

    /**
     * One record of what the scheduler did.
     *
     * @param jobId {@code null} for a recalculation
     * @param targetKey what the step was for, e.g. {@code day:2026-09-27} or {@code D7}
     * @param detail a JSON object
     */
    public record Event(UUID id, UUID organizationId, UUID storeId, UUID policyId, UUID jobId,
                        String datasetKind, String kind, String targetKey, String detail, UUID ingestionRunId,
                        UUID calculationRunId, Instant occurredAt) {
    }
}

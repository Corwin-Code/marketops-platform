package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.listingconversion.BatchView;
import com.mimococo.marketops.listingconversion.ContainmentCauseClass;
import com.mimococo.marketops.listingconversion.ContainmentView;
import com.mimococo.marketops.listingconversion.LateAssociationView;
import com.mimococo.marketops.listingconversion.RecalculationClass;
import com.mimococo.marketops.listingconversion.RecalculationQueueView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Batches, containment, isolation dependencies, late associations and the
 * recalculation queue.
 *
 * <p>Containment rows and their attestations are written only through their
 * database functions, which consume a one-use invocation proof.
 */
@Repository
public class GovernanceRepository {

    private final JdbcClient jdbc;

    GovernanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ batches

    public void insertBatch(UUID id, UUID organizationId, UUID storeId, String code, UUID createdBy, Instant now) {
        jdbc.sql("""
                INSERT INTO ops.lc_batch (id, organization_id, store_id, batch_code, created_by_user_id, created_at, state,
                    updated_at, version)
                VALUES (:id, :org, :store, :code, :user, :now, 'OPEN', :now, 0)
                """).param("id", id).param("org", organizationId).param("store", storeId).param("code", code)
                .param("user", createdBy).param("now", Timestamp.from(now)).update();
    }

    public void insertMember(UUID id, UUID organizationId, UUID batchId, UUID actionId, String membershipState,
                             UUID recordedBy, Instant now) {
        int sequence = jdbc.sql("SELECT coalesce(max(sequence_no), 0) + 1 FROM ops.lc_batch_member WHERE batch_id = :batch AND action_id = :action")
                .param("batch", batchId).param("action", actionId).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO ops.lc_batch_member (id, organization_id, batch_id, action_id, sequence_no, membership_state,
                    recorded_by_user_id, recorded_at)
                VALUES (:id, :org, :batch, :action, :sequence, :state, :user, :now)
                """).param("id", id).param("org", organizationId).param("batch", batchId).param("action", actionId)
                .param("sequence", sequence).param("state", membershipState).param("user", recordedBy)
                .param("now", Timestamp.from(now)).update();
    }

    public boolean closeBatch(UUID id, long expectedVersion, Instant now) {
        return jdbc.sql("UPDATE ops.lc_batch SET state = 'CLOSED', updated_at = :now, version = version + 1 WHERE id = :id AND version = :version AND state = 'OPEN'")
                .param("id", id).param("version", expectedVersion).param("now", Timestamp.from(now)).update() == 1;
    }

    public Optional<BatchView> batch(UUID id) {
        return jdbc.sql("SELECT id, store_id, batch_code, created_by_user_id, created_at, state, version FROM ops.lc_batch WHERE id = :id")
                .param("id", id).query(this::mapBatch).optional();
    }

    public List<BatchView> batches(UUID organizationId, List<UUID> storeIds, int limit) {
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT id, store_id, batch_code, created_by_user_id, created_at, state, version FROM ops.lc_batch
                 WHERE organization_id = :org AND store_id IN (:stores) ORDER BY created_at DESC LIMIT :limit
                """).param("org", organizationId).param("stores", storeIds).param("limit", limit).query(this::mapBatch).list();
    }

    private BatchView mapBatch(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<BatchView.Member> members = jdbc.sql("""
                SELECT m.action_id, a.platform_listing_id, m.membership_state, m.sequence_no, a.state AS action_state, m.recorded_at
                  FROM ops.lc_batch_member m JOIN ops.lc_action a ON a.id = m.action_id
                 WHERE m.batch_id = :batch
                   AND m.sequence_no = (SELECT max(latest.sequence_no) FROM ops.lc_batch_member latest
                                         WHERE latest.batch_id = m.batch_id AND latest.action_id = m.action_id)
                 ORDER BY m.recorded_at
                """).param("batch", id)
                .query((row, m) -> new BatchView.Member(row.getObject("action_id", UUID.class),
                        row.getObject("platform_listing_id", UUID.class), row.getString("membership_state"),
                        row.getInt("sequence_no"), row.getString("action_state"), ListingFactRepository.instant(row, "recorded_at")))
                .list();
        return new BatchView(id, rs.getObject("store_id", UUID.class), rs.getString("batch_code"),
                rs.getObject("created_by_user_id", UUID.class), ListingFactRepository.instant(rs, "created_at"),
                rs.getString("state"), members, rs.getLong("version"));
    }

    // ------------------------------------------------------------------ containment

    public UUID recordContainment(UUID id, UUID actorId, UUID organizationId, String proof, String scopeKind, UUID listingId,
                                  UUID storeId, String platformCode, UUID batchId, ContainmentCauseClass cause,
                                  String causeOwnerRole, String reason, String evidence) {
        return jdbc.sql("""
                SELECT ops.record_lc_containment(:id, :actor, :org, :proof, :scope, :listing, :store, :platform, :batch,
                        :cause, :role, :reason, :evidence)
                """).param("id", id).param("actor", actorId).param("org", organizationId).param("proof", proof)
                .param("scope", scopeKind).param("listing", listingId).param("store", storeId).param("platform", platformCode)
                .param("batch", batchId).param("cause", cause.name()).param("role", causeOwnerRole).param("reason", reason)
                .param("evidence", evidence).query(UUID.class).single();
    }

    public UUID attest(UUID id, UUID containmentId, UUID actorId, String proof, String kind, String evidence) {
        return jdbc.sql("SELECT ops.attest_lc_containment(:id, :containment, :actor, :proof, :kind, :evidence)")
                .param("id", id).param("containment", containmentId).param("actor", actorId).param("proof", proof)
                .param("kind", kind).param("evidence", evidence).query(UUID.class).single();
    }

    public void reenable(UUID containmentId, UUID actorId) {
        jdbc.sql("SELECT ops.reenable_lc_containment(:id, :actor)").param("id", containmentId).param("actor", actorId)
                .query(Object.class).optional();
    }

    public Optional<ContainmentView> containment(UUID id) {
        return jdbc.sql(CONTAINMENT_SELECT + " WHERE c.id = :id").param("id", id).query(this::mapContainment).optional();
    }

    public List<ContainmentView> containments(UUID organizationId, boolean activeOnly, int limit) {
        return jdbc.sql(CONTAINMENT_SELECT + """
                 WHERE c.organization_id = :org AND (NOT :active OR c.state = 'ACTIVE') ORDER BY c.stopped_at DESC LIMIT :limit
                """).param("org", organizationId).param("active", activeOnly).param("limit", limit).query(this::mapContainment).list();
    }

    private static final String CONTAINMENT_SELECT = """
            SELECT c.id, c.scope_kind, c.platform_listing_id, c.store_id, c.platform_code, c.batch_id, c.cause_class,
                   c.cause_owner_role_code, c.stopped_by_user_id, c.stopped_at, c.reason, c.evidence_reference, c.state,
                   c.reenabled_at
              FROM ops.lc_containment c
            """;

    private ContainmentView mapContainment(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<ContainmentView.Attestation> attestations = jdbc.sql("""
                SELECT id, attestation_kind, actor_user_id, evidence_reference, attested_at FROM ops.lc_containment_attestation
                 WHERE containment_id = :containment ORDER BY attested_at
                """).param("containment", id)
                .query((row, m) -> new ContainmentView.Attestation(row.getObject("id", UUID.class), row.getString("attestation_kind"),
                        row.getObject("actor_user_id", UUID.class), row.getString("evidence_reference"),
                        ListingFactRepository.instant(row, "attested_at")))
                .list();
        return new ContainmentView(id, rs.getString("scope_kind"), rs.getObject("platform_listing_id", UUID.class),
                rs.getObject("store_id", UUID.class), rs.getString("platform_code"), rs.getObject("batch_id", UUID.class),
                ContainmentCauseClass.valueOf(rs.getString("cause_class")), rs.getString("cause_owner_role_code"),
                rs.getObject("stopped_by_user_id", UUID.class), ListingFactRepository.instant(rs, "stopped_at"),
                rs.getString("reason"), rs.getString("evidence_reference"), rs.getString("state"),
                ListingFactRepository.instant(rs, "reenabled_at"), attestations);
    }

    public void insertDependency(UUID id, UUID organizationId, UUID fromListing, UUID toListing, String kind, String proof,
                                 UUID recordedBy, Instant now) {
        jdbc.sql("""
                INSERT INTO ops.lc_isolation_dependency (id, organization_id, from_listing_id, to_listing_id, dependency_kind,
                    proof_reference, proven_at, recorded_by_user_id)
                VALUES (:id, :org, :from, :to, :kind, :proof, :now, :user)
                """).param("id", id).param("org", organizationId).param("from", fromListing).param("to", toListing)
                .param("kind", kind).param("proof", proof).param("now", Timestamp.from(now)).param("user", recordedBy).update();
    }

    public Map<UUID, List<UUID>> provenDependencies(UUID organizationId) {
        Map<UUID, List<UUID>> graph = new java.util.HashMap<>();
        jdbc.sql("SELECT from_listing_id, to_listing_id FROM ops.lc_isolation_dependency WHERE organization_id = :org")
                .param("org", organizationId)
                .query((rs, n) -> new UUID[] {rs.getObject("from_listing_id", UUID.class), rs.getObject("to_listing_id", UUID.class)})
                .list()
                .forEach(pair -> graph.computeIfAbsent(pair[0], ignored -> new java.util.ArrayList<>()).add(pair[1]));
        return graph;
    }

    // ------------------------------------------------------------------ late association

    public void insertLateAssociation(UUID id, UUID organizationId, UUID listingId, UUID observationId, UUID actionId,
                                      String kind, Instant operationTime, Instant reportTime, String authorityGap,
                                      String forwardDisposition, String state, UUID recordedBy, Instant now) {
        jdbc.sql("""
                INSERT INTO ops.lc_late_association (id, organization_id, platform_listing_id, observation_id, action_id,
                    association_kind, operation_time, report_time, authority_gap, forward_disposition, state,
                    recorded_by_user_id, recorded_at, updated_at, version)
                VALUES (:id, :org, :listing, :observation, :action, :kind, :operation, :report, :gap, :disposition, :state,
                    :user, :now, :now, 0)
                """).param("id", id).param("org", organizationId).param("listing", listingId).param("observation", observationId)
                .param("action", actionId).param("kind", kind).param("operation", ListingFactRepository.ts(operationTime))
                .param("report", ListingFactRepository.ts(reportTime)).param("gap", authorityGap)
                .param("disposition", forwardDisposition).param("state", state).param("user", recordedBy)
                .param("now", Timestamp.from(now)).update();
    }

    public boolean closeLateAssociation(UUID id, UUID verificationId, long expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE ops.lc_late_association SET state = 'CLOSED', closure_verification_id = :verification, updated_at = :now,
                    version = version + 1
                 WHERE id = :id AND version = :version AND state <> 'CLOSED'
                """).param("id", id).param("verification", verificationId).param("version", expectedVersion)
                .param("now", Timestamp.from(now)).update() == 1;
    }

    public List<LateAssociationView> lateAssociations(UUID listingId) {
        return jdbc.sql(LATE_SELECT + " WHERE platform_listing_id = :listing ORDER BY recorded_at DESC")
                .param("listing", listingId).query(GovernanceRepository::mapLate).list();
    }

    public Optional<LateAssociationView> lateAssociation(UUID id) {
        return jdbc.sql(LATE_SELECT + " WHERE id = :id").param("id", id).query(GovernanceRepository::mapLate).optional();
    }

    private static final String LATE_SELECT = """
            SELECT id, platform_listing_id, action_id, association_kind, observation_id, operation_time, report_time,
                   authority_gap, forward_disposition, state, closure_verification_id, recorded_by_user_id, recorded_at, version
              FROM ops.lc_late_association
            """;

    private static LateAssociationView mapLate(ResultSet rs, int n) throws SQLException {
        return new LateAssociationView(rs.getObject("id", UUID.class), rs.getObject("platform_listing_id", UUID.class),
                rs.getObject("action_id", UUID.class), rs.getString("association_kind"), rs.getObject("observation_id", UUID.class),
                ListingFactRepository.instant(rs, "operation_time"), ListingFactRepository.instant(rs, "report_time"),
                rs.getString("authority_gap"), rs.getString("forward_disposition"), rs.getString("state"),
                rs.getObject("closure_verification_id", UUID.class), rs.getObject("recorded_by_user_id", UUID.class),
                ListingFactRepository.instant(rs, "recorded_at"));
    }

    public long lateAssociationVersion(UUID id) {
        return jdbc.sql("SELECT version FROM ops.lc_late_association WHERE id = :id").param("id", id).query(Long.class).single();
    }

    // ------------------------------------------------------------------ recalculation queue

    public void enqueue(UUID id, UUID organizationId, UUID listingId, RecalculationClass triggerClass, String reference,
                        Instant sourceTime, Instant acceptedAt) {
        jdbc.sql("""
                INSERT INTO ops.lc_recalculation_queue (id, organization_id, platform_listing_id, trigger_class, target_minutes,
                    trigger_reference, source_time, accepted_at, state)
                VALUES (:id, :org, :listing, :class, :target, :reference, :source, :accepted, 'QUEUED')
                """).param("id", id).param("org", organizationId).param("listing", listingId).param("class", triggerClass.name())
                .param("target", triggerClass.targetMinutes()).param("reference", reference)
                .param("source", ListingFactRepository.ts(sourceTime)).param("accepted", Timestamp.from(acceptedAt)).update();
    }

    public record QueuedRow(UUID id, UUID organizationId, UUID listingId, RecalculationClass triggerClass,
                            long leaseGeneration) {
    }

    /** The existing queue is the sole lease authority. A statement claims only rows it actually updates. */
    public List<QueuedRow> claim(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid recalculation limit");
        return jdbc.sql("""
                WITH tick AS MATERIALIZED (SELECT clock_timestamp() AS at)
                UPDATE ops.lc_recalculation_queue q
                   SET state='RUNNING', started_at=coalesce(q.started_at,tick.at),
                       lease_generation=coalesce(q.lease_generation,0)+1,
                       leased_until=tick.at+interval '120 seconds'
                  FROM (SELECT pending.id FROM ops.lc_recalculation_queue pending CROSS JOIN tick
                         WHERE pending.accepted_at<=tick.at AND (pending.state='QUEUED' OR (pending.state='RUNNING' AND
                             (pending.leased_until IS NULL OR pending.leased_until<=tick.at)))
                         ORDER BY CASE pending.trigger_class WHEN 'RISK' THEN 0 WHEN 'ORDINARY' THEN 1 ELSE 2 END,
                                  pending.accepted_at,pending.id
                         LIMIT :limit FOR UPDATE OF pending SKIP LOCKED) ready, tick
                 WHERE q.id=ready.id
                RETURNING q.id,q.organization_id,q.platform_listing_id,q.trigger_class,q.lease_generation
                """).param("limit",limit)
                .query((rs,n)->new QueuedRow(rs.getObject("id",UUID.class),rs.getObject("organization_id",UUID.class),
                        rs.getObject("platform_listing_id",UUID.class),RecalculationClass.valueOf(rs.getString("trigger_class")),
                        rs.getLong("lease_generation"))).list();
    }

    /** Hold the lease row through result publication; an expired worker must roll its entire result back. */
    public boolean lockClaim(QueuedRow row) {
        return jdbc.sql("""
                SELECT id FROM ops.lc_recalculation_queue WHERE id=:id AND state='RUNNING'
                   AND lease_generation=:generation AND leased_until>clock_timestamp()
                 FOR UPDATE
                """).param("id",row.id()).param("generation",row.leaseGeneration()).query(UUID.class).optional().isPresent();
    }

    public boolean finish(QueuedRow row, UUID healthId, String failureCode) {
        return jdbc.sql("""
                UPDATE ops.lc_recalculation_queue q
                   SET state=CASE WHEN :failure IS NULL THEN 'FINISHED' ELSE 'FAILED' END,
                       finished_at=clock_timestamp(),health_result_id=:health,
                       calculation_run_id=(SELECT calculation_run_id FROM mart.lc_listing_health WHERE id=:health),
                       failure_code=:failure,leased_until=NULL
                 WHERE q.id=:id AND q.state='RUNNING' AND q.lease_generation=:generation
                   AND q.leased_until>clock_timestamp()
                """).param("id",row.id()).param("generation",row.leaseGeneration())
                .param("health",new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.OTHER,healthId))
                .param("failure",new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.VARCHAR,failureCode)).update()==1;
    }

    public List<RecalculationQueueView> queue(UUID organizationId, int limit) {
        return jdbc.sql("""
                SELECT id, trigger_class, target_minutes, platform_listing_id, trigger_reference, source_time, accepted_at,
                       started_at, finished_at, state, health_result_id, calculation_run_id
                  FROM ops.lc_recalculation_queue WHERE organization_id = :org ORDER BY accepted_at DESC LIMIT :limit
                """).param("org", organizationId).param("limit", limit)
                .query((rs, n) -> {
                    Instant accepted = ListingFactRepository.instant(rs, "accepted_at");
                    Instant finished = ListingFactRepository.instant(rs, "finished_at");
                    Integer latency = finished == null ? null : (int) java.time.Duration.between(accepted, finished).getSeconds();
                    int target = rs.getInt("target_minutes");
                    return new RecalculationQueueView(rs.getObject("id", UUID.class),
                            RecalculationClass.valueOf(rs.getString("trigger_class")), target,
                            rs.getObject("platform_listing_id", UUID.class), rs.getString("trigger_reference"),
                            ListingFactRepository.instant(rs, "source_time"), accepted,
                            ListingFactRepository.instant(rs, "started_at"), finished, rs.getString("state"), latency,
                            "FINISHED".equals(rs.getString("state")) && rs.getObject("health_result_id") != null
                                    && rs.getObject("calculation_run_id") != null
                                    && finished != null && !finished.isBefore(accepted)
                                    && java.time.Duration.between(accepted, finished).compareTo(java.time.Duration.ofMinutes(target)) <= 0);
                })
                .list();
    }
}

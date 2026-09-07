package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The targeted queue, the scan cursor and the sweep run.
 *
 * <p>Three behaviours here are the reason the loop is trustworthy rather than
 * merely present.
 *
 * <p>Coalescing keeps the <em>earliest</em> accepted instant. A second fact
 * arriving while the first is still queued must not restart the response clock,
 * or an object under continuous change would never breach an SLO no matter how
 * long it went unhandled.
 *
 * <p>Suppression refuses work that a same-or-newer answer already covers, so
 * re-reading a feed boundary is a no-op rather than a loop.
 *
 * <p>The cursor position is a total key — instant, provenance and item — and can
 * only move forward. Two facts accepted in the same microsecond cannot make the
 * scan skip one, and a replayed scan cannot rewind past work it already did.
 */
@Repository
public class AdvertisingRecalculationRepository {

    private static final String FEED_CODE = "ADVERTISING_ACCEPTED_FACT";

    private final JdbcClient jdbc;

    AdvertisingRecalculationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What happened when work was offered to the queue. */
    public enum EnqueueOutcome {

        /** A new request was created. */
        CREATED,

        /** An existing pending request absorbed it, keeping the earlier instant. */
        COALESCED,

        /** A same-or-newer answer already covers it. */
        SUPPRESSED
    }

    /** One offered unit of work. */
    public record NewRequest(
            UUID id, UUID organizationId, UUID adNativeObjectId, String triggerClass,
            String triggerReference, Instant factAcceptedAt, Instant requestedAt,
            String correlationId) {
    }

    public EnqueueOutcome enqueue(NewRequest request) {
        int coalesced = jdbc.sql("""
                UPDATE ops.ad_recalculation_request
                   SET fact_accepted_at = LEAST(fact_accepted_at, :factAcceptedAt),
                       latest_fact_accepted_at = GREATEST(latest_fact_accepted_at, :factAcceptedAt),
                       next_fact_accepted_at = CASE WHEN state='LEASED' AND :factAcceptedAt>calculation_as_of
                          THEN LEAST(coalesce(next_fact_accepted_at,:factAcceptedAt),:factAcceptedAt)
                          ELSE next_fact_accepted_at END,
                       version = version + 1
                 WHERE organization_id = :organizationId
                   AND ad_native_object_id = :adNativeObjectId
                   AND state IN ('PENDING', 'LEASED')
                """)
                .param("organizationId", request.organizationId())
                .param("adNativeObjectId", request.adNativeObjectId())
                .param("factAcceptedAt", ts(request.factAcceptedAt()))
                .update();
        if (coalesced > 0) {
            return EnqueueOutcome.COALESCED;
        }
        int inserted = jdbc.sql("""
                INSERT INTO ops.ad_recalculation_request (
                    id, organization_id, ad_native_object_id, trigger_class, trigger_reference,
                    fact_accepted_at, requested_at, state, correlation_id)
                SELECT :id, :organizationId, :adNativeObjectId, :triggerClass, :triggerReference,
                       :factAcceptedAt, :requestedAt, 'PENDING', :correlationId
                 WHERE NOT EXISTS (
                     SELECT 1 FROM ops.ad_recalculation_request prior
                      WHERE prior.organization_id = :organizationId
                        AND prior.ad_native_object_id = :adNativeObjectId
                        AND prior.fact_accepted_at >= :factAcceptedAt)
                """)
                .param("id", request.id())
                .param("organizationId", request.organizationId())
                .param("adNativeObjectId", request.adNativeObjectId())
                .param("triggerClass", request.triggerClass())
                .param("triggerReference", request.triggerReference())
                .param("factAcceptedAt", ts(request.factAcceptedAt()))
                .param("requestedAt", ts(request.requestedAt()))
                .param("correlationId", request.correlationId())
                .update();
        return inserted > 0 ? EnqueueOutcome.CREATED : EnqueueOutcome.SUPPRESSED;
    }

    /** A claimed unit of work, leased to one worker. */
    public record ClaimedRequest(
            UUID id, UUID organizationId, UUID adNativeObjectId, String triggerClass,
            String triggerReference, Instant factAcceptedAt, int attemptCount,
            String correlationId, Instant calculationAsOf, String leaseOwner) {
    }

    public int deliverDue(Instant now,int limit) {
        return jdbc.sql("SELECT ops.deliver_due_ad_recalculations(:at,:limit)")
                .param("at",ts(now)).param("limit",limit).query(Integer.class).single();
    }

    public List<ClaimedRequest> claim(String owner, Instant leasedUntil, int limit, Instant now) {
        return jdbc.sql("""
                UPDATE ops.ad_recalculation_request target
                   SET state = 'LEASED', leased_until = :leasedUntil, lease_owner = :owner,
                       attempt_count = target.attempt_count + 1, completed_at=NULL, failure_code=NULL,
                       started_at = coalesce(target.started_at, :now), calculation_as_of=:now, next_fact_accepted_at=NULL,
                       version = target.version + 1
                  FROM (SELECT id FROM ops.ad_recalculation_request
                         WHERE ((state = 'PENDING' OR (state='FAILED' AND attempt_count<5)) AND fact_accepted_at<=:now
                            OR (state = 'LEASED' AND leased_until < :now))
                           AND (state<>'FAILED' OR NOT EXISTS(SELECT 1 FROM ops.ad_recalculation_request active
                             WHERE active.organization_id=ops.ad_recalculation_request.organization_id
                               AND active.ad_native_object_id=ops.ad_recalculation_request.ad_native_object_id
                               AND active.id<>ops.ad_recalculation_request.id
                               AND (active.state IN ('PENDING','LEASED') OR active.state='FAILED'
                                 AND active.attempt_count<5 AND (active.fact_accepted_at,active.id)<
                                   (ops.ad_recalculation_request.fact_accepted_at,ops.ad_recalculation_request.id))))
                         ORDER BY EXISTS(SELECT 1 FROM mart.ad_case critical
                             WHERE critical.ad_native_object_id=ops.ad_recalculation_request.ad_native_object_id
                               AND critical.lane='PROTECTION') DESC,
                           EXISTS(SELECT 1 FROM ops.ad_containment held
                             WHERE held.organization_id=ops.ad_recalculation_request.organization_id
                               AND held.ad_native_object_id=ops.ad_recalculation_request.ad_native_object_id
                               AND held.state<>'REENABLED') DESC,
                           fact_accepted_at,id
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED) AS claimable
                 WHERE target.id = claimable.id
             RETURNING target.id, target.organization_id, target.ad_native_object_id,
                       target.trigger_class, target.trigger_reference, target.fact_accepted_at,
                       target.attempt_count, target.correlation_id,target.calculation_as_of,target.lease_owner
                """)
                .param("owner", owner)
                .param("leasedUntil", ts(leasedUntil))
                .param("limit", limit)
                .param("now", ts(now))
                .query((ResultSet rs, int index) -> new ClaimedRequest(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("ad_native_object_id", UUID.class),
                        rs.getString("trigger_class"),
                        rs.getString("trigger_reference"),
                        instantOf(rs, "fact_accepted_at"),
                        rs.getInt("attempt_count"),
                        rs.getString("correlation_id"),instantOf(rs,"calculation_as_of"),rs.getString("lease_owner")))
                .list();
    }

    /** An expired lease cannot acknowledge a successor worker's work or swallow a newer event. */
    public void finish(ClaimedRequest request,String state,String failureCode,Instant completedAt) {
        jdbc.sql("""
                UPDATE ops.ad_recalculation_request SET
                  state=CASE WHEN :state='COMPLETED' AND next_fact_accepted_at IS NOT NULL THEN 'PENDING' ELSE :state END,
                  fact_accepted_at=CASE WHEN :state='COMPLETED' THEN coalesce(next_fact_accepted_at,fact_accepted_at) ELSE fact_accepted_at END,
                  completed_at=CASE WHEN :state='COMPLETED' AND next_fact_accepted_at IS NOT NULL THEN NULL ELSE CAST(:at AS timestamptz) END,
                  failure_code=:failure,lease_owner=NULL,leased_until=NULL,next_fact_accepted_at=NULL,version=version+1
                WHERE id=:id AND state='LEASED' AND lease_owner=:owner AND calculation_as_of=:asof
                """).param("state",state).param("failure",failureCode).param("at",ts(completedAt))
                .param("id",request.id()).param("owner",request.leaseOwner()).param("asof",ts(request.calculationAsOf())).update();
    }

    public void finish(UUID id, String state, String failureCode, Instant completedAt) {
        jdbc.sql("""
                UPDATE ops.ad_recalculation_request
                   SET state = :state, failure_code = :failureCode, completed_at = :completedAt,
                       lease_owner = NULL, leased_until = NULL, version = version + 1
                 WHERE id = :id
                """)
                .param("id", id).param("state", state).param("failureCode", failureCode)
                .param("completedAt", ts(completedAt))
                .update();
    }

    /**
     * Close every request the sweep has just answered.
     *
     * <p>This is how a dropped trigger becomes a recovered one: the sweep visits
     * the object anyway, and any request still waiting for it is completed rather
     * than left to expire.
     */
    public int repairCoveredRequests(UUID organizationId, List<UUID> objectIds, Instant asOf, Instant at) {
        if (objectIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE ops.ad_recalculation_request
                   SET state = 'COMPLETED', completed_at = :at, lease_owner = NULL,
                       leased_until = NULL, version = version + 1
                 WHERE organization_id = :organizationId
                   AND ad_native_object_id = ANY (:objectIds)
                   AND state IN ('PENDING', 'LEASED','FAILED','ABANDONED')
                   AND latest_fact_accepted_at<=:asOf
                """)
                .param("asOf",ts(asOf)).param("organizationId", organizationId)
                .param("objectIds", objectIds.toArray(new UUID[0]))
                .param("at", ts(at))
                .update();
    }

    public int repairCoveredRequests(UUID organizationId,List<UUID> objectIds,Instant at) {
        return repairCoveredRequests(organizationId,objectIds,at,at);
    }

    public record Unanswered(UUID id,UUID objectId,Instant acceptedAt) { }
    public List<Unanswered> unanswered(UUID organizationId,UUID objectId,Instant asOf) {
        return jdbc.sql("""
                SELECT id,ad_native_object_id,fact_accepted_at FROM ops.ad_recalculation_request
                WHERE organization_id=:org AND ad_native_object_id=:object
                  AND state IN('PENDING','LEASED','FAILED','ABANDONED') AND latest_fact_accepted_at<=:asof
                """).param("org",organizationId).param("object",objectId).param("asof",ts(asOf))
                .query((rs,n)->new Unanswered(rs.getObject("id",UUID.class),rs.getObject("ad_native_object_id",UUID.class),instantOf(rs,"fact_accepted_at"))).list();
    }

    public int releaseProvenReservations(UUID organizationId) {
        return jdbc.sql("""
                SELECT count(*) FROM (SELECT ops.release_ad_action_reservation(id,'hourly canonical proof reconciliation') released
                    FROM ops.ad_action_reservation WHERE organization_id=:org AND state<>'RELEASED') checked WHERE released
                """).param("org",organizationId).query(Integer.class).single();
    }

    /** How much work is waiting and how old the oldest of it is. */
    public record Backlog(long pending, Instant oldestFactAcceptedAt) {
    }

    public Backlog backlog(UUID organizationId) {
        return jdbc.sql("""
                SELECT count(*) AS pending, min(fact_accepted_at) AS oldest
                  FROM ops.ad_recalculation_request
                 WHERE organization_id = :organizationId AND state IN ('PENDING', 'LEASED')
                """)
                .param("organizationId", organizationId)
                .query((ResultSet rs, int index) -> new Backlog(
                        rs.getLong("pending"), instantOf(rs, "oldest")))
                .single();
    }

    /** The scan position, as a total key. */
    public record CursorPosition(Instant positionAt, UUID provenanceId, String itemKey) {

        /** The position a first scan starts from: everything, from the beginning. */
        public static CursorPosition beginning() {
            return new CursorPosition(Instant.EPOCH, new UUID(0, 0), "");
        }
    }

    public void startCursor(Instant from) {
        jdbc.sql("""
                INSERT INTO ops.ad_fact_cursor (
                    feed_code, position_at, position_provenance_id, position_item_key,
                    last_scanned_at, scanned_count)
                VALUES (:feedCode, :from, :zero, '', :from, 0)
                ON CONFLICT (feed_code) DO NOTHING
                """)
                .param("feedCode", FEED_CODE)
                .param("from", ts(from))
                .param("zero", new UUID(0, 0))
                .update();
    }

    public CursorPosition cursorPosition() {
        return jdbc.sql("""
                SELECT position_at, position_provenance_id, position_item_key
                  FROM ops.ad_fact_cursor WHERE feed_code = :feedCode
                """)
                .param("feedCode", FEED_CODE)
                .query((ResultSet rs, int index) -> new CursorPosition(
                        instantOf(rs, "position_at"),
                        rs.getObject("position_provenance_id", UUID.class),
                        rs.getString("position_item_key")))
                .optional()
                .orElseGet(CursorPosition::beginning);
    }

    /** Advance the cursor. The predicate makes a rewind impossible. */
    public void advanceCursor(CursorPosition to, Instant scannedAt, long scannedCount) {
        jdbc.sql("""
                UPDATE ops.ad_fact_cursor
                   SET position_at = :positionAt,
                       position_provenance_id = :provenanceId,
                       position_item_key = :itemKey,
                       last_scanned_at = :scannedAt,
                       scanned_count = scanned_count + :scannedCount,
                       version = version + 1
                 WHERE feed_code = :feedCode
                   AND (position_at, position_provenance_id, position_item_key)
                       <= (:positionAt, :provenanceId, :itemKey)
                """)
                .param("feedCode", FEED_CODE)
                .param("positionAt", ts(to.positionAt()))
                .param("provenanceId", to.provenanceId())
                .param("itemKey", to.itemKey())
                .param("scannedAt", ts(scannedAt))
                .param("scannedCount", scannedCount)
                .update();
    }

    /** Organizations with advertising objects worth sweeping. */
    public List<UUID> activeOrganizations() {
        return jdbc.sql("""
                SELECT DISTINCT o.id FROM core.organization o
                  JOIN core.ad_native_object obj ON obj.organization_id = o.id
                 WHERE o.status = 'ACTIVE' AND obj.status = 'ACTIVE'
                 ORDER BY o.id
                """)
                .query(UUID.class)
                .list();
    }

    /** One hourly sweep. */
    public record RunOutcome(
            UUID id, String state, int objectCount, int changedCaseCount, int repairedCount,
            int expiredExceptionCount, int expiredApprovalCount, int releasedReservationCount,
            int failedObjectCount, UUID lastObjectId, String failureCode, Instant completedAt) {
    }

    /**
     * Start a sweep, or decline because one is already running.
     *
     * <p>The partial unique index is the mutex. A second concurrent sweep would
     * make the targeted-equals-sweep property untestable, so a duplicate key is
     * an ordinary answer here rather than an error.
     */
    public boolean startRun(UUID runId, UUID organizationId, Instant asOf, String triggerKind,
            Instant startedAt, String correlationId) {
        try {
            jdbc.sql("""
                    INSERT INTO ops.ad_reconciliation_run (
                        id, organization_id, as_of, state, trigger_kind, started_at, correlation_id)
                    VALUES (:id, :organizationId, :asOf, 'RUNNING', :triggerKind, :startedAt,
                            :correlationId)
                    """)
                    .param("id", runId).param("organizationId", organizationId)
                    .param("asOf", ts(asOf)).param("triggerKind", triggerKind)
                    .param("startedAt", ts(startedAt)).param("correlationId", correlationId)
                    .update();
            return true;
        } catch (DuplicateKeyException alreadyRunning) {
            return false;
        }
    }

    public void recordRunProgress(UUID runId, UUID lastObjectId, int visited, int changed,
            int failed) {
        jdbc.sql("""
                UPDATE ops.ad_reconciliation_run
                   SET last_ad_native_object_id = :lastObjectId, object_count = :visited,
                       changed_case_count = :changed, failed_object_count = :failed
                 WHERE id = :runId
                """)
                .param("runId", runId).param("lastObjectId", lastObjectId)
                .param("visited", visited).param("changed", changed).param("failed", failed)
                .update();
    }

    public void finishRun(RunOutcome outcome) {
        jdbc.sql("""
                UPDATE ops.ad_reconciliation_run
                   SET state = :state, object_count = :objectCount,
                       changed_case_count = :changedCaseCount, repaired_count = :repairedCount,
                       expired_exception_count = :expiredExceptionCount,
                       expired_approval_count = :expiredApprovalCount,
                       released_reservation_count = :releasedReservationCount,
                       failed_object_count = :failedObjectCount,
                       last_ad_native_object_id = :lastObjectId,
                       failure_code = :failureCode, completed_at = :completedAt
                 WHERE id = :id
                """)
                .param("id", outcome.id()).param("state", outcome.state())
                .param("objectCount", outcome.objectCount())
                .param("changedCaseCount", outcome.changedCaseCount())
                .param("repairedCount", outcome.repairedCount())
                .param("expiredExceptionCount", outcome.expiredExceptionCount())
                .param("expiredApprovalCount", outcome.expiredApprovalCount())
                .param("releasedReservationCount", outcome.releasedReservationCount())
                .param("failedObjectCount", outcome.failedObjectCount())
                .param("lastObjectId", outcome.lastObjectId())
                .param("failureCode", outcome.failureCode())
                .param("completedAt", ts(outcome.completedAt()))
                .update();
    }

    /**
     * Close runs that outlived a full cadence.
     *
     * <p>A worker that was killed mid-sweep leaves a RUNNING row that would hold
     * the mutex for ever. Closing it as FAILED preserves its progress marker so
     * the next sweep is a recovery rather than a restart.
     */
    public int failAbandonedRuns(UUID organizationId, Instant olderThan, Instant at) {
        return jdbc.sql("""
                UPDATE ops.ad_reconciliation_run
                   SET state = 'FAILED', failure_code = 'WORKER_INTERRUPTED', completed_at = :at
                 WHERE organization_id = :organizationId AND state = 'RUNNING'
                   AND started_at < :olderThan
                """)
                .param("organizationId", organizationId)
                .param("olderThan", ts(olderThan))
                .param("at", ts(at))
                .update();
    }

    /** The most recent completed sweep, for the operability check. */
    public Optional<Instant> lastCompletedSweepAt(UUID organizationId) {
        return jdbc.sql("""
                SELECT max(completed_at) AS max FROM ops.ad_reconciliation_run
                 WHERE organization_id = :organizationId AND state = 'COMPLETED'
                """)
                .param("organizationId", organizationId)
                .query((ResultSet rs, int index) -> instantOf(rs, "max"))
                .optional();
    }

    /**
     * Bind an instant the driver can type.
     *
     * <p>PostgreSQL's driver cannot infer a SQL type for {@link java.time.Instant},
     * and a bare {@code null} is worse: it has no type at all. Wrapping both in a
     * typed parameter value is what the rest of this codebase does, and doing it
     * anywhere else would produce a runtime failure that only shows up on the
     * path that happens to pass a null.
     */
    private static org.springframework.jdbc.core.SqlParameterValue ts(java.time.Instant instant) {
        return new org.springframework.jdbc.core.SqlParameterValue(
                java.sql.Types.TIMESTAMP,
                instant == null ? null : java.sql.Timestamp.from(instant));
    }

    /**
     * Read a timestamp the driver will hand over.
     *
     * <p>This driver refuses {@code getObject(column, Instant.class)} against a
     * {@code timestamptz}, so every read goes through {@link java.sql.Timestamp}
     * exactly as the rest of this codebase does. Null stays null rather than
     * becoming the epoch, because an absent observation time and an observation
     * at the dawn of time are different facts.
     */
    private static java.time.Instant instantOf(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}

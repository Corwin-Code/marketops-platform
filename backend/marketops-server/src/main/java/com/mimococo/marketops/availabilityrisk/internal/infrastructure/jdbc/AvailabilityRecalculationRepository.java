package com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.mimococo.marketops.operatingfacts.AcceptedFactCursor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The queue, the sweep and the latency evidence behind recalculation.
 *
 * <p>Every rule that has to survive concurrency lives in SQL here rather than in
 * a service that reads and then decides. Two workers claiming the same request,
 * two sweeps of one organization, two pending requests for one variant: each is
 * refused by an index or a predicate, because the read-then-decide shape is
 * exactly the shape a second thread walks through.
 */
@Repository
public class AvailabilityRecalculationRepository {

    /** The one feed this Slice reads. */
    public static final String ACCEPTED_FACT_FEED = "ACCEPTED_FACT";

    private final JdbcClient jdbc;

    public AvailabilityRecalculationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Where the feed has been read to, when it has been read at all. */
    public Optional<AcceptedFactCursor> cursorPosition() {
        return jdbc.sql("""
                        SELECT position_at, position_provenance_id, position_item_key
                          FROM ops.availability_fact_cursor
                         WHERE feed_code = :feed
                        """)
                .param("feed", ACCEPTED_FACT_FEED)
                .query((rows, rowNumber) -> new AcceptedFactCursor(
                        rows.getTimestamp("position_at").toInstant(),
                        rows.getObject("position_provenance_id", UUID.class),
                        rows.getString("position_item_key")))
                .optional();
    }

    /**
     * Start the feed at an instant, unless it has already started.
     *
     * <p>A fresh installation starts at the caller's explicit backfill point.
     * The insert is conditional so two workers starting together produce one
     * position, and neither can silently skip facts accepted before startup.
     */
    public void startCursor(Instant at) {
        jdbc.sql("""
                        INSERT INTO ops.availability_fact_cursor
                            (feed_code, position_at, position_provenance_id, position_item_key,
                             last_scanned_at, scanned_count)
                        VALUES (:feed, :at, :beforeAny, '', :at, 0)
                        ON CONFLICT (feed_code) DO NOTHING
                        """)
                .param("feed", ACCEPTED_FACT_FEED)
                .param("at", Timestamp.from(at))
                .param("beforeAny", AcceptedFactCursor.BEFORE_ANY_PROVENANCE)
                .update();
    }

    /**
     * Move the position forward, never back.
     *
     * <p>The predicate is the whole safety property: a worker that finished late
     * cannot rewind a position another worker has already advanced past, and a
     * rewind would replay facts that were already answered.
     */
    public void advanceCursor(AcceptedFactCursor position, Instant scannedAt, int scanned) {
        jdbc.sql("""
                        UPDATE ops.availability_fact_cursor
                           SET position_at = :positionAt,
                               position_provenance_id = :positionProvenanceId,
                               position_item_key = :positionItemKey,
                               last_scanned_at = :scannedAt,
                               scanned_count = scanned_count + :scanned,
                               version = version + 1
                         WHERE feed_code = :feed
                           AND (position_at, position_provenance_id, position_item_key)
                               <= (:positionAt, :positionProvenanceId, :positionItemKey)
                        """)
                .param("feed", ACCEPTED_FACT_FEED)
                .param("positionAt", Timestamp.from(position.ingestionTime()))
                .param("positionProvenanceId", position.provenanceId())
                .param("positionItemKey", position.itemKey())
                .param("scannedAt", Timestamp.from(scannedAt))
                .param("scanned", scanned)
                .update();
    }

    /**
     * Fold one accepted fact into the pending work for its variant.
     *
     * <p>A hundred observations in a minute are one recalculation. When work is
     * already pending the earliest accepted instant wins, because that is the
     * one the response obligation is judged against; a later fact must not be
     * able to restart a clock that has been running.
     *
     * @return whether this fact created or moved pending work
     */
    public boolean enqueue(NewRequest request) {
        int coalesced = jdbc.sql("""
                        UPDATE ops.availability_recalculation_request
                           SET fact_accepted_at = LEAST(fact_accepted_at, :factAcceptedAt),
                               version = version + 1
                         WHERE organization_id = :organizationId
                           AND product_variant_id = :productVariantId
                           AND state IN ('PENDING', 'LEASED')
                        """)
                .param("organizationId", request.organizationId())
                .param("productVariantId", request.productVariantId())
                .param("factAcceptedAt", Timestamp.from(request.factAcceptedAt()))
                .update();
        if (coalesced > 0) {
            return true;
        }
        // Nothing pending. Insert unless this variant has already been
        // recalculated for a fact at least as recent, which is what makes
        // re-reading the feed boundary a no-op instead of a loop.
        return jdbc.sql("""
                        INSERT INTO ops.availability_recalculation_request
                            (id, organization_id, product_variant_id, trigger_class,
                             trigger_reference, fact_accepted_at, requested_at, state,
                             correlation_id)
                        SELECT :id, :organizationId, :productVariantId, :triggerClass,
                               :triggerReference, :factAcceptedAt, :requestedAt, 'PENDING',
                               :correlationId
                         WHERE NOT EXISTS (
                               SELECT 1 FROM ops.availability_recalculation_request AS prior
                                WHERE prior.organization_id = :organizationId
                                  AND prior.product_variant_id = :productVariantId
                                  AND prior.fact_accepted_at >= :factAcceptedAt)
                        """)
                .param("id", request.id())
                .param("organizationId", request.organizationId())
                .param("productVariantId", request.productVariantId())
                .param("triggerClass", request.triggerClass())
                .param("triggerReference", request.triggerReference())
                .param("factAcceptedAt", Timestamp.from(request.factAcceptedAt()))
                .param("requestedAt", Timestamp.from(request.requestedAt()))
                .param("correlationId", request.correlationId())
                .update() > 0;
    }

    /**
     * Take a bounded amount of work, oldest fact first.
     *
     * <p>{@code SKIP LOCKED} is what lets two workers share the queue without
     * either waiting for the other, and the expired-lease branch is what stops a
     * worker that died mid-request from stranding it forever.
     */
    public List<ClaimedRequest> claim(String leaseOwner, Instant leasedUntil, int limit,
                                      Instant now) {
        return jdbc.sql("""
                        UPDATE ops.availability_recalculation_request AS target
                           SET state = 'LEASED',
                               leased_until = :leasedUntil,
                               lease_owner = :leaseOwner,
                               attempt_count = target.attempt_count + 1,
                               started_at = coalesce(target.started_at, :now),
                               version = target.version + 1
                          FROM (
                                SELECT id FROM ops.availability_recalculation_request
                                 WHERE state = 'PENDING'
                                    OR (state = 'LEASED' AND leased_until < :now)
                                 ORDER BY fact_accepted_at
                                 LIMIT :limit
                                 FOR UPDATE SKIP LOCKED
                               ) AS ready
                         WHERE target.id = ready.id
                        RETURNING target.id, target.organization_id, target.product_variant_id,
                                  target.trigger_class, target.trigger_reference,
                                  target.fact_accepted_at, target.attempt_count,
                                  target.correlation_id
                        """)
                .param("leaseOwner", leaseOwner)
                .param("leasedUntil", Timestamp.from(leasedUntil))
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rows, rowNumber) -> new ClaimedRequest(
                        rows.getObject("id", UUID.class),
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getString("trigger_class"),
                        rows.getString("trigger_reference"),
                        rows.getTimestamp("fact_accepted_at").toInstant(),
                        rows.getInt("attempt_count"),
                        rows.getString("correlation_id")))
                .list();
    }

    /** Finish one request, successfully or not. */
    public void finish(UUID id, String state, String failureCode, Instant at) {
        jdbc.sql("""
                        UPDATE ops.availability_recalculation_request
                           SET state = :state, failure_code = :failureCode, completed_at = :at,
                               leased_until = NULL, lease_owner = NULL, version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id).param("state", state).param("failureCode", failureCode)
                .param("at", Timestamp.from(at))
                .update();
    }

    /**
     * Close the pending work a sweep has just covered.
     *
     * <p>This is the repair path. A trigger that was dropped, or a worker that
     * died holding one, leaves a request nobody will finish; the sweep
     * recalculated that variant anyway, so the request is satisfied and saying
     * so is what turns a lost trigger into a recovered one.
     *
     * @return how many stranded requests this sweep repaired
     */
    public int repairCoveredRequests(UUID organizationId, List<UUID> productVariantIds,
                                     Instant at) {
        if (productVariantIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE ops.availability_recalculation_request
                           SET state = 'COMPLETED', completed_at = :at,
                               leased_until = NULL, lease_owner = NULL, version = version + 1
                         WHERE organization_id = :organizationId
                           AND product_variant_id = ANY (:variantIds)
                           AND state IN ('PENDING', 'LEASED')
                        """)
                .param("organizationId", organizationId)
                .param("variantIds", productVariantIds.toArray(UUID[]::new))
                .param("at", Timestamp.from(at))
                .update();
    }

    /** How much work is waiting, and how old the oldest of it is. */
    public Backlog backlog(UUID organizationId, Instant now) {
        return jdbc.sql("""
                        SELECT count(*) AS pending,
                               coalesce(max(EXTRACT(EPOCH FROM (:now - fact_accepted_at))), 0)
                                   AS oldest_seconds
                          FROM ops.availability_recalculation_request
                         WHERE organization_id = :organizationId
                           AND state IN ('PENDING', 'LEASED')
                        """)
                .param("organizationId", organizationId)
                .param("now", Timestamp.from(now))
                .query((rows, rowNumber) -> new Backlog(rows.getInt("pending"),
                        java.time.Duration.ofSeconds(
                                (long) rows.getDouble("oldest_seconds"))))
                .single();
    }

    /** Open a sweep. The active-run index refuses a second one. */
    public void startRun(UUID id, UUID organizationId, Instant asOf, String triggerKind,
                         Instant startedAt, String correlationId) {
        jdbc.sql("""
                        INSERT INTO ops.availability_reconciliation_run
                            (id, organization_id, as_of, state, trigger_kind, started_at,
                             correlation_id)
                        VALUES (:id, :organizationId, :asOf, 'RUNNING', :triggerKind, :startedAt,
                                :correlationId)
                        """)
                .param("id", id).param("organizationId", organizationId)
                .param("asOf", Timestamp.from(asOf)).param("triggerKind", triggerKind)
                .param("startedAt", Timestamp.from(startedAt))
                .param("correlationId", correlationId)
                .update();
    }

    /**
     * Fail an interrupted sweep once it has exceeded the hourly operating envelope.
     *
     * <p>A process can disappear after writing durable progress but before it
     * writes completion. Leaving that row {@code RUNNING} forever would make
     * the uniqueness guard suppress every later hourly repair. The next worker
     * therefore closes only runs older than the complete cadence, preserving
     * both their last keyset position and the fact that they never completed.
     */
    public int failAbandonedRuns(UUID organizationId, Instant abandonedBefore,
                                 Instant detectedAt) {
        return jdbc.sql("""
                        UPDATE ops.availability_reconciliation_run
                           SET state = 'FAILED', failure_code = 'WORKER_INTERRUPTED',
                               completed_at = :detectedAt
                         WHERE organization_id = :organizationId
                           AND state = 'RUNNING'
                           AND started_at <= :abandonedBefore
                        """)
                .param("organizationId", organizationId)
                .param("abandonedBefore", Timestamp.from(abandonedBefore))
                .param("detectedAt", Timestamp.from(detectedAt))
                .update();
    }

    /** Close a sweep with what it actually did. */
    public void finishRun(RunOutcome outcome) {
        jdbc.sql("""
                        UPDATE ops.availability_reconciliation_run
                           SET state = :state, variant_count = :variantCount,
                               changed_card_count = :changedCount,
                               repaired_count = :repairedCount,
                               failed_variant_count = :failedCount,
                               expired_inbound_count = :expiredInbound,
                               expired_exception_count = :expiredException,
                               failure_code = :failureCode, completed_at = :completedAt
                         WHERE id = :id
                        """)
                .param("id", outcome.runId()).param("state", outcome.state())
                .param("variantCount", outcome.variantCount())
                .param("changedCount", outcome.changedCardCount())
                .param("repairedCount", outcome.repairedCount())
                .param("failedCount", outcome.failedVariantCount())
                .param("expiredInbound", outcome.expiredInboundCount())
                .param("expiredException", outcome.expiredExceptionCount())
                .param("failureCode", outcome.failureCode())
                .param("completedAt", Timestamp.from(outcome.completedAt()))
                .update();
    }

    /** When the organization last completed a sweep, if it ever has. */
    public Optional<Instant> lastCompletedRun(UUID organizationId) {
        return jdbc.sql("""
                        SELECT max(completed_at) AS completed_at
                          FROM ops.availability_reconciliation_run
                         WHERE organization_id = :organizationId AND state = 'COMPLETED'
                        """)
                .param("organizationId", organizationId)
                .query((rows, rowNumber) -> rows.getTimestamp("completed_at"))
                .optional()
                .map(timestamp -> timestamp == null ? null : timestamp.toInstant());
    }

    /** Whether a sweep of this organization is already in flight. */
    public boolean runInFlight(UUID organizationId) {
        Long count = jdbc.sql("""
                        SELECT count(*) FROM ops.availability_reconciliation_run
                         WHERE organization_id = :organizationId AND state = 'RUNNING'
                        """)
                .param("organizationId", organizationId)
                .query(Long.class).single();
        return count != null && count > 0;
    }

    /**
     * Every organization a sweep has to cover.
     *
     * <p>Read from the topology rather than from configuration so that an
     * organization created after the process started is swept without a
     * restart. An organization nobody has retired still has an availability
     * question.
     */
    public List<UUID> activeOrganizations() {
        return jdbc.sql("""
                        SELECT id FROM core.organization WHERE status = 'ACTIVE' ORDER BY code
                        """)
                .query(UUID.class)
                .list();
    }

    /**
     * The portfolio one sweep has to visit.
     *
     * <p>Derived from what is actually mapped and sellable rather than from the
     * catalogue: a variant nobody lists has no availability question, and
     * sweeping it would spend the hour's budget on rows with no answer.
     */
    public List<UUID> variantsToReconcile(UUID organizationId, Instant asOf,
                                          UUID afterVariantId, int limit) {
        return jdbc.sql("""
                        SELECT DISTINCT mapping.product_variant_id
                          FROM core.listing_mapping AS mapping
                         WHERE mapping.organization_id = :organizationId
                           AND mapping.status = 'ACTIVE'
                           AND mapping.effective_from <= :asOf
                           AND (mapping.effective_to IS NULL OR mapping.effective_to > :asOf)
                           AND (CAST(:afterVariantId AS uuid) IS NULL
                                OR mapping.product_variant_id > :afterVariantId)
                         ORDER BY 1
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("asOf", Timestamp.from(asOf))
                .param("afterVariantId", afterVariantId)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** Persist progress after each page so interruption cannot look like completion. */
    public void recordRunProgress(UUID runId, UUID lastVariantId, int visited, int changed,
                                  int failures) {
        jdbc.sql("""
                        UPDATE ops.availability_reconciliation_run
                           SET last_product_variant_id = :lastVariantId,
                               variant_count = :visited,
                               changed_card_count = :changed,
                               failed_variant_count = :failures
                         WHERE id = :runId AND state = 'RUNNING'
                        """)
                .param("runId", runId).param("lastVariantId", lastVariantId)
                .param("visited", visited).param("changed", changed)
                .param("failures", failures).update();
    }

    /**
     * The lane a variant's card currently shows, when it has one.
     *
     * <p>Read before a sweep recalculates it so the sweep can report how many
     * answers actually moved. A sweep that changed nothing and a sweep that
     * changed everything are different operational events, and a run record
     * that could not tell them apart would be no use in a review.
     */
    public Optional<String> cardLane(UUID organizationId, UUID productVariantId) {
        return jdbc.sql("""
                        SELECT lane FROM mart.availability_risk_card
                         WHERE organization_id = :organizationId
                           AND product_variant_id = :productVariantId
                        """)
                .param("organizationId", organizationId)
                .param("productVariantId", productVariantId)
                .query(String.class)
                .optional();
    }

    /** Append one recalculation's latency evidence. */
    public void recordObservation(SloObservation observation) {
        jdbc.sql("""
                        INSERT INTO ops.availability_slo_observation
                            (id, organization_id, product_variant_id, lane, path_kind,
                             source_event_time, source_updated_at, ingested_at, fact_accepted_at,
                             risk_calculated_at, case_updated_at, internal_latency_ms,
                             source_latency_ms, breached, correlation_id)
                        VALUES (:id, :organizationId, :productVariantId, :lane, :pathKind,
                                :sourceEventTime, :sourceUpdatedAt, :ingestedAt, :factAcceptedAt,
                                :calculatedAt, :caseUpdatedAt, :internalLatency, :sourceLatency,
                                :breached, :correlationId)
                        """)
                .param("id", observation.id())
                .param("organizationId", observation.organizationId())
                .param("productVariantId", observation.productVariantId())
                .param("lane", observation.lane()).param("pathKind", observation.pathKind())
                .param("sourceEventTime", timestamp(observation.sourceEventTime()))
                .param("sourceUpdatedAt", timestamp(observation.sourceUpdatedAt()))
                .param("ingestedAt", timestamp(observation.ingestedAt()))
                .param("factAcceptedAt", Timestamp.from(observation.factAcceptedAt()))
                .param("calculatedAt", Timestamp.from(observation.riskCalculatedAt()))
                .param("caseUpdatedAt", timestamp(observation.caseUpdatedAt()))
                .param("internalLatency", observation.internalLatencyMillis())
                .param("sourceLatency", observation.sourceLatencyMillis())
                .param("breached", observation.breached())
                .param("correlationId", observation.correlationId())
                .update();
    }

    /**
     * How the targeted path performed for one lane over a window.
     *
     * <p>The percentile is computed in the database over the retained
     * observations rather than kept as a running aggregate, because an
     * aggregate cannot be re-examined after the fact and this number is
     * evidence somebody may have to defend.
     */
    public LatencySummary latencySummary(UUID organizationId, String lane, Instant from,
                                         Instant to) {
        return jdbc.sql("""
                        SELECT count(*) AS observations,
                               coalesce(max(internal_latency_ms), 0) AS worst,
                               coalesce(percentile_disc(0.95) WITHIN GROUP
                                   (ORDER BY internal_latency_ms), 0) AS p95,
                               count(*) FILTER (WHERE breached) AS breaches
                          FROM ops.availability_slo_observation
                         WHERE organization_id = :organizationId
                           AND lane = :lane
                           AND risk_calculated_at >= :from
                           AND risk_calculated_at < :to
                        """)
                .param("organizationId", organizationId).param("lane", lane)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new LatencySummary(rows.getInt("observations"),
                        rows.getLong("p95"), rows.getLong("worst"), rows.getInt("breaches")))
                .single();
    }

    /**
     * When the source said a fact was true and when acquisition stored it.
     *
     * <p>Read at completion rather than carried on the queue row, because the
     * source timings belong to the fact and duplicating them onto the work item
     * would create a second place for them to be wrong.
     */
    public Optional<SourceTiming> provenanceTiming(UUID provenanceId) {
        return jdbc.sql("""
                        SELECT source_time, ingestion_time
                          FROM core.fact_provenance
                         WHERE id = :provenanceId
                        """)
                .param("provenanceId", provenanceId)
                .query((rows, rowNumber) -> new SourceTiming(
                        rows.getTimestamp("source_time") == null
                                ? null : rows.getTimestamp("source_time").toInstant(),
                        rows.getTimestamp("ingestion_time") == null
                                ? null : rows.getTimestamp("ingestion_time").toInstant()))
                .optional();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /**
     * One fact turned into work.
     *
     * @param id the request
     * @param organizationId owning organization
     * @param productVariantId the variant to recalculate
     * @param triggerClass which kind of evidence changed
     * @param triggerReference the exact fact, or {@code null}
     * @param factAcceptedAt when the fact entered the system
     * @param requestedAt when the worker noticed
     * @param correlationId the scan's own identity
     */
    public record NewRequest(UUID id, UUID organizationId, UUID productVariantId,
                             String triggerClass, String triggerReference, Instant factAcceptedAt,
                             Instant requestedAt, String correlationId) {
    }

    /**
     * One request a worker now holds.
     *
     * @param id the request
     * @param organizationId owning organization
     * @param productVariantId the variant to recalculate
     * @param triggerClass which kind of evidence changed
     * @param triggerReference the exact fact, or {@code null}
     * @param factAcceptedAt where the response clock started
     * @param attemptCount how many times this has been tried
     * @param correlationId the scan that produced it
     */
    public record ClaimedRequest(UUID id, UUID organizationId, UUID productVariantId,
                                 String triggerClass, String triggerReference,
                                 Instant factAcceptedAt, int attemptCount,
                                 String correlationId) {
    }

    /**
     * When a fact happened and when it was stored.
     *
     * @param sourceTime when the source considered it true, or {@code null}
     * @param ingestionTime when acquisition stored it
     */
    public record SourceTiming(Instant sourceTime, Instant ingestionTime) {
    }

    /**
     * What is waiting.
     *
     * @param pending how many requests are unfinished
     * @param oldestAge how long the oldest has been waiting since its fact
     */
    public record Backlog(int pending, java.time.Duration oldestAge) {
    }

    /**
     * What one sweep did.
     *
     * @param runId the sweep
     * @param state {@code COMPLETED} or {@code FAILED}
     * @param variantCount variants visited
     * @param changedCardCount cards whose lane moved
     * @param repairedCount stranded requests the sweep satisfied
     * @param expiredInboundCount inbound claims that lapsed
     * @param expiredExceptionCount acceptances that lapsed
     * @param failureCode why it failed, or {@code null}
     * @param completedAt when it ended
     */
    public record RunOutcome(UUID runId, String state, Integer variantCount,
                             Integer changedCardCount, Integer repairedCount,
                             Integer failedVariantCount,
                             Integer expiredInboundCount, Integer expiredExceptionCount,
                             String failureCode, Instant completedAt) {
    }

    /**
     * One recalculation's latency evidence.
     *
     * @param id the observation
     * @param organizationId owning organization
     * @param productVariantId the variant recalculated
     * @param lane the lane the card ended at
     * @param pathKind {@code TARGETED} or {@code RECONCILIATION}
     * @param sourceEventTime when the source said it happened, or {@code null}
     * @param sourceUpdatedAt when the source last updated it, or {@code null}
     * @param ingestedAt when acquisition stored it, or {@code null}
     * @param factAcceptedAt where the internal clock started
     * @param riskCalculatedAt when the answer existed
     * @param caseUpdatedAt when the case was brought up to date, or {@code null}
     * @param internalLatencyMillis how long MarketOps took
     * @param sourceLatencyMillis how long the source took, or {@code null}
     * @param breached whether the internal obligation was missed
     * @param correlationId the run's own identity
     */
    public record SloObservation(UUID id, UUID organizationId, UUID productVariantId, String lane,
                                 String pathKind, Instant sourceEventTime, Instant sourceUpdatedAt,
                                 Instant ingestedAt, Instant factAcceptedAt,
                                 Instant riskCalculatedAt, Instant caseUpdatedAt,
                                 long internalLatencyMillis, Long sourceLatencyMillis,
                                 boolean breached, String correlationId) {
    }

    /**
     * How a lane's targeted path performed.
     *
     * @param observations how many recalculations were measured
     * @param p95LatencyMillis the 95th percentile internal latency
     * @param worstLatencyMillis the slowest internal latency
     * @param breaches how many missed the hard obligation
     */
    public record LatencySummary(int observations, long p95LatencyMillis, long worstLatencyMillis,
                                 int breaches) {
    }
}

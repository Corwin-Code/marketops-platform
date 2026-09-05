package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.domain.AdvertisingSlo;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingRecalculationRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingTraceRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hourly sweep that repairs whatever the targeted path missed.
 *
 * <p>The targeted path can drop work: a trigger that was never enqueued, a
 * request abandoned after five attempts, a fact that arrived while a worker was
 * being restarted. None of those are visible from inside the targeted path,
 * which is why the sweep exists and why it visits every object rather than only
 * the ones with pending requests.
 *
 * <p>It also closes any request covering an object it just visited, so a dropped
 * trigger becomes a recovered one rather than an entry that eventually expires.
 *
 * <p>One run per organization at a time, enforced by a partial unique index
 * rather than a lock. A second concurrent sweep would make the
 * targeted-equals-sweep property untestable, so a refused start is an ordinary
 * answer here rather than an error.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
class AdvertisingReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(AdvertisingReconciliationWorker.class);

    /** How many objects one keyset page carries. */
    private static final int PORTFOLIO_PAGE = 1_000;

    /** A run older than a full cadence was interrupted and is closed as failed. */
    private static final Duration MAX_RUN_AGE = Duration.ofHours(1);

    private final AdvertisingRecalculationRepository queue;
    private final AdvertisingEvidenceRepository facts;
    private final AdvertisingCaseRefreshService refresh;
    private final AdvertisingTraceRepository trace;
    private final IdGenerator ids;
    private final Clock clock;
    private final com.mimococo.marketops.operationsworkflow.AdvertisingReconciliationMaintenance maintenance;
    private final AdvertisingOutcomeWorker outcomes;
    private final AdvertisingOrchestrationSloService slo;

    AdvertisingReconciliationWorker(
            AdvertisingRecalculationRepository queue,
            AdvertisingEvidenceRepository facts,
            AdvertisingCaseRefreshService refresh,
            AdvertisingTraceRepository trace,
            IdGenerator ids,
            Clock clock,
            com.mimococo.marketops.operationsworkflow.AdvertisingReconciliationMaintenance maintenance,
            AdvertisingOutcomeWorker outcomes,AdvertisingOrchestrationSloService slo) {
        this.queue = queue;
        this.facts = facts;
        this.refresh = refresh;
        this.trace = trace;
        this.ids = ids;
        this.clock = clock;this.maintenance=maintenance;this.outcomes=outcomes;this.slo=slo;
    }

    /** What one sweep did. */
    record SweepResult(
            UUID runId, boolean completed, int objectCount, int changedCaseCount,
            int repairedCount, int failedObjectCount) {
    }

    Optional<SweepResult> sweep(UUID organizationId, String triggerKind) {
        Instant asOf = clock.instant();
        queue.failAbandonedRuns(organizationId, asOf.minus(MAX_RUN_AGE), asOf);

        UUID runId = ids.newId();
        String correlationId = "advertising-sweep:" + runId;
        if (!queue.startRun(runId, organizationId, asOf, triggerKind, asOf, correlationId)) {
            // Another sweep holds the mutex. Declining is the correct answer and
            // not a failure, so it is traced rather than logged as an error.
            trace.record(ids.newId(), organizationId, null, "RECONCILIATION",
                    "SWEEP_STARTED", "SUPPRESSED", correlationId, null, null,
                    "{\"reason\":\"another sweep is running\"}", asOf);
            return Optional.empty();
        }
        trace.record(ids.newId(), organizationId, null, "RECONCILIATION", "SWEEP_STARTED",
                "STARTED", correlationId, null, null, "{}", asOf);

        var backlog = queue.backlog(organizationId);
        trace.record(ids.newId(), organizationId, null, "OPERATIONS", "BACKLOG_SNAPSHOT",
                "OBSERVED", correlationId, null, null,
                "{\"pending\":" + backlog.pending() + "}", asOf);

        var maintained=maintenance.reconcile(organizationId,asOf);
        queue.deliverDue(asOf,10000);
        List<UUID> visited = new ArrayList<>();
        int changed = 0;
        int failed = 0;
        UUID after = null;
        boolean completed = true;

        while (true) {
            List<AdvertisingEvidenceRepository.ObjectRow> page =
                    facts.objectsToReconcile(organizationId, after, PORTFOLIO_PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (AdvertisingEvidenceRepository.ObjectRow object : page) {
                after = object.id();
                try {
                    var outcomeBatch=outcomes.runForObject(organizationId,object.id(),asOf,1000);
                    if(outcomeBatch.remaining()) throw new IllegalStateException("AD_OUTCOME_BACKLOG_REMAINS");
                    Optional<AdvertisingCaseRefreshService.RefreshOutcome> outcome =
                            refresh.refresh(organizationId, object.id(), asOf,
                                    AdvertisingProjectionWriter.RECONCILIATION, runId,
                                    correlationId);
                    visited.add(object.id());
                    Instant observedAt=clock.instant();
                    var firstCase=outcome.stream().flatMap(value->value.written().cases().stream())
                            .sorted(java.util.Comparator.comparing(value->!"PROTECTION".equals(value.lane()))).findFirst();
                    for(var unanswered:queue.unanswered(organizationId,object.id(),asOf)) {
                        Duration latency=Duration.between(unanswered.acceptedAt(),observedAt);
                        trace.recordSlo(ids.newId(),organizationId,object.id(),firstCase.map(value->value.caseId()).orElse(null),
                                firstCase.map(value->value.lane()).orElse("WATCH"),"RECONCILIATION",null,null,null,
                                unanswered.acceptedAt(),observedAt,observedAt,
                                latency.isNegative()?null:latency.toMillis(),null,
                                latency.isNegative()||AdvertisingSlo.breached(latency),correlationId+":"+unanswered.id());
                    }
                    if (outcome.map(result -> result.written().anyLaneChanged()).orElse(false)) {
                        changed++;
                    }
                } catch (RuntimeException failure) {
                    // One object's failure costs one object. The run still finishes
                    // and reports itself failed, so a partial sweep is visible
                    // rather than indistinguishable from a complete one.
                    failed++;
                    completed = false;
                    log.warn("event=advertising_sweep_object_failed adNativeObjectId={} reason={}",
                            object.id(), failure.toString());
                }
            }
            queue.recordRunProgress(runId, after, visited.size(), changed, failed);
        }

        int repaired = queue.repairCoveredRequests(organizationId, visited, asOf,clock.instant());
        int released=queue.releaseProvenReservations(organizationId);
        Instant completedAt = clock.instant();
        queue.finishRun(new AdvertisingRecalculationRepository.RunOutcome(
                runId, completed ? "COMPLETED" : "FAILED", visited.size(), changed, repaired,
                maintained.expiredExceptions(), maintained.expiredApprovals(), released, failed, after,
                completed ? null : "ADVERTISING_SWEEP_INCOMPLETE", completedAt));
        trace.record(ids.newId(), organizationId, null, "RECONCILIATION",
                completed ? "SWEEP_COMPLETED" : "SWEEP_FAILED",
                completed ? "COMPLETED" : "FAILED", correlationId, null, null,
                "{\"objects\":" + visited.size() + ",\"changed\":" + changed
                        + ",\"repaired\":" + repaired + ",\"failed\":" + failed + "}",
                completedAt);

        slo.record(organizationId);
        return Optional.of(new SweepResult(
                runId, completed, visited.size(), changed, repaired, failed));
    }
}

package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.listingconversion.RecalculationClass;
import com.mimococo.marketops.listingconversion.RecalculationQueueView;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.GovernanceRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the recalculation queue, class by class, and records the latency.
 *
 * <p>RISK before ORDINARY before FULL_REVIEW. Each item is its own committed
 * step, so a failed recalculation records its failure and does not hold the
 * rest of the queue.
 */
@Service
public class RecalculationService {

    private static final Logger log = LoggerFactory.getLogger(RecalculationService.class);

    private final GovernanceRepository governance;
    private final ListingHealthService health;
    private final IdGenerator ids;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private final com.mimococo.marketops.operationsworkflow.ListingTaskDeferralIntake deferrals;
    private final com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold dependencyHolds;
    private final com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository actions;
    private final ConversionMeasurementService measurements;
    private final EvaluationService evaluations;

    RecalculationService(GovernanceRepository governance, ListingHealthService health, IdGenerator ids, Clock clock,
                         org.springframework.transaction.PlatformTransactionManager transactions,
                         com.mimococo.marketops.operationsworkflow.ListingTaskDeferralIntake deferrals,
                         com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository actions,
                         ConversionMeasurementService measurements,
                         EvaluationService evaluations,
                         com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold dependencyHolds) {
        this.governance = governance;
        this.health = health;
        this.ids = ids;
        this.clock = clock;
        this.deferrals = deferrals;
        this.dependencyHolds = dependencyHolds;
        this.actions = actions;
        this.measurements = measurements;
        this.evaluations = evaluations;
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(120);
    }

    @Transactional
    public UUID enqueue(UUID organizationId, UUID listingId, RecalculationClass triggerClass, String reference,
                        Instant sourceTime) {
        UUID id = ids.newId();
        governance.enqueue(id, organizationId, listingId, triggerClass, reference, sourceTime, clock.instant());
        return id;
    }

    @Transactional(propagation = Propagation.NEVER)
    public int runOnce(int limit) {
        transaction.executeWithoutResult(status -> {
            dependencyHolds.synchronizeDue(limit);
            for(var review:deferrals.expireDue(limit)) {
                UUID queueId=ids.newId();
                governance.enqueue(queueId,review.organizationId(),review.listingId(),
                        review.necessaryRisk()?RecalculationClass.RISK:RecalculationClass.ORDINARY,
                        "task-deferral-expired:"+review.id(),review.expiredAt(),governance.databaseNow());
                deferrals.queued(review.id(),queueId);
            }
        });
        int finished = 0;
        for (GovernanceRepository.QueuedRow row : governance.claim(limit)) {
            try {
                Boolean completed = transaction.execute(status -> {
                    if (!governance.lockClaim(row)) return false;
                    var result = health.recompute(row.listingId(), "SCHEDULED", null);
                    var measurementResults=measurements.remeasureCurrent(row.listingId(),"SCHEDULED");
                    var outcomeResults=evaluations.reviseCurrent(row.listingId(),measurementResults,
                            "recalculation-queue:"+row.id());
                    var bindingResults=actions.recheckPendingBindings(row.listingId());
                    if (!governance.finish(row, result.id(),measurementResults,bindingResults.assessed(),
                            bindingResults.invalidated(),outcomeResults.assessed(),outcomeResults.resultIds(),null)) {
                        throw new IllegalStateException("recalculation lease expired before publication");
                    }
                    return true;
                });
                if (Boolean.TRUE.equals(completed)) finished++;
            } catch (RuntimeException failure) {
                log.warn("event=lc_recalculation_failed queueId={} failureType={}", row.id(),
                        failure.getClass().getSimpleName());
                // A stale worker cannot fail a successor's claim. An expired lease remains recoverable.
                transaction.executeWithoutResult(status -> governance.finish(row, null, "recalculation_failed"));
            }
        }
        return finished;
    }

    @Transactional
    public int enqueueDueFullReviews(int limit) {
        return governance.enqueueDueFullReviews(limit);
    }

    @Transactional(readOnly = true)
    public List<RecalculationQueueView> queue(UUID organizationId, int limit) {
        return governance.queue(organizationId, limit);
    }
}

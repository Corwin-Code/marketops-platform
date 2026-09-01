package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.InboundAttestationRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityTraceRepository;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionGovernance;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recalculates the whole portfolio, on a cadence, whatever targeting did.
 *
 * <p>The sweep exists to catch what targeting missed: a dropped trigger, a fact
 * that arrived out of order, an inbound or an acceptance that expired while
 * nothing was watching, a worker that stopped mid-request. Its value is that it
 * needs no theory about what went wrong — it recalculates everything and lets
 * the answers replace whatever was there.
 *
 * <p>It closes the requests it covered rather than leaving them pending. A
 * variant the sweep has just recalculated does not need recalculating again,
 * and saying so is what turns a lost trigger into a recovered one instead of a
 * queue that never drains.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class AvailabilityReconciliationWorker {

    private static final Logger LOG =
            LoggerFactory.getLogger(AvailabilityReconciliationWorker.class);

    /** Bounded database page; a run continues until every page is exhausted. */
    private static final int PORTFOLIO_PAGE = 1_000;

    /** An unfinished run older than one complete cadence is an interrupted run. */
    private static final Duration MAX_RUN_AGE = Duration.ofHours(1);

    private final AvailabilityRecalculationRepository queue;
    private final AvailabilityRiskRefreshService refresh;
    private final AvailabilityExceptionGovernance exceptions;
    private final InboundAttestationRepository inbound;
    private final IdGenerator ids;
    private final Clock clock;
    private final AvailabilityTraceRepository trace;

    public AvailabilityReconciliationWorker(AvailabilityRecalculationRepository queue,
                                            AvailabilityRiskRefreshService refresh,
                                            AvailabilityExceptionGovernance exceptions,
                                            InboundAttestationRepository inbound,
                                            IdGenerator ids, Clock clock,
                                            AvailabilityTraceRepository trace) {
        this.queue = queue;
        this.refresh = refresh;
        this.exceptions = exceptions;
        this.inbound = inbound;
        this.ids = ids;
        this.clock = clock;
        this.trace = trace;
    }

    /**
     * Sweep one organization's whole portfolio once.
     *
     * @param organizationId the organization to sweep
     * @param triggerKind {@code SCHEDULED}, {@code MANUAL} or {@code RECOVERY}
     * @return what the sweep did, or empty when one was already in flight
     */
    public java.util.Optional<SweepResult> sweep(UUID organizationId, String triggerKind) {
        Instant asOf = clock.instant();
        UUID runId = ids.newId();
        String correlationId = "availability-sweep:" + runId;
        queue.failAbandonedRuns(organizationId, asOf.minus(MAX_RUN_AGE), asOf);
        try {
            queue.startRun(runId, organizationId, asOf, triggerKind, asOf, correlationId);
        } catch (DuplicateKeyException alreadyRunning) {
            // One sweep of an organization at a time. A second concurrent sweep
            // would make "the targeted result equals the sweep result"
            // untestable, because neither would know which evidence the other
            // read.
            LOG.info("a reconciliation sweep of organization {} is already in flight",
                    organizationId);
            return java.util.Optional.empty();
        }
        trace.record(organizationId, null, "RECONCILIATION", "SWEEP_STARTED", "STARTED",
                correlationId, null, runId.toString(), "{}", asOf);
        var backlog = queue.backlog(organizationId, asOf);
        trace.record(organizationId, null, "OPERATIONS", "BACKLOG_SNAPSHOT", "OBSERVED",
                correlationId, null, runId.toString(),
                "{\"pending\":" + backlog.pending() + ",\"oldestSeconds\":"
                        + backlog.oldestAge().toSeconds() + "}", asOf);

        int changed = 0;
        int failures = 0;
        List<UUID> visited = new ArrayList<>();
        List<UUID> successful = new ArrayList<>();
        UUID after = null;
        while (true) {
            List<UUID> variants = queue.variantsToReconcile(
                    organizationId, asOf, after, PORTFOLIO_PAGE);
            if (variants.isEmpty()) {
                break;
            }
            for (UUID variantId : variants) {
                visited.add(variantId);
                after = variantId;
                try {
                    if (visit(organizationId, variantId, asOf, runId, correlationId)) {
                        changed++;
                    }
                    successful.add(variantId);
                } catch (RuntimeException failure) {
                    failures++;
                    LOG.error("reconciliation sweep {} could not refresh variant {}",
                            runId, variantId, failure);
                }
            }
            queue.recordRunProgress(runId, after, visited.size(), changed, failures);
        }

        Instant completedAt = clock.instant();
        int repaired = queue.repairCoveredRequests(organizationId, successful, completedAt);
        int expiredExceptions = exceptions.expireDue(organizationId, completedAt).size();
        exceptions.revalidateActive(organizationId, completedAt);
        trace.record(organizationId, null, "OPERATIONS",
                "EXCEPTION_EXPIRY_REVALIDATION", "COMPLETED", correlationId, null,
                runId.toString(), "{\"expired\":" + expiredExceptions + "}", completedAt);
        int lapsedInbound = inbound.countLapsed(organizationId, asOf);
        String state = failures == 0 ? "COMPLETED" : "FAILED";
        queue.finishRun(new AvailabilityRecalculationRepository.RunOutcome(runId, state,
                visited.size(), changed, repaired, failures, lapsedInbound, expiredExceptions,
                failures == 0 ? null : "VARIANT_REFRESH_FAILED", completedAt));
        trace.record(organizationId, null, "RECONCILIATION",
                failures == 0 ? "SWEEP_COMPLETED" : "SWEEP_FAILED",
                failures == 0 ? "COMPLETED" : "FAILED", correlationId, null,
                runId.toString(), "{\"variants\":" + visited.size()
                        + ",\"failures\":" + failures + "}", completedAt);
        return java.util.Optional.of(new SweepResult(runId, failures == 0, visited.size(), changed,
                repaired, lapsedInbound, expiredExceptions, failures));
    }

    /**
     * Recalculate one variant inside a sweep.
     *
     * <p>One variant's failure is recorded and isolated. The run continues so
     * every later variant is still inspected, then finishes {@code FAILED}
     * rather than claiming full success over the skipped answer.
     *
     * @return whether the card's lane moved
     */
    private boolean visit(UUID organizationId, UUID variantId, Instant asOf, UUID runId,
                          String parentCorrelationId) {
        AvailabilityLane before = lane(organizationId, variantId);
        AvailabilityRiskRefreshService.RefreshOutcome outcome = refresh.refresh(organizationId,
                variantId, asOf, AvailabilityRiskRefreshService.RECONCILIATION, runId,
                parentCorrelationId);
        return before != outcome.written().lane();
    }

    private AvailabilityLane lane(UUID organizationId, UUID variantId) {
        return queue.cardLane(organizationId, variantId).map(AvailabilityLane::valueOf)
                .orElse(null);
    }

    /**
     * What one sweep did.
     *
     * @param runId the sweep
     * @param completed whether it finished the portfolio
     * @param variantCount variants visited
     * @param changedCardCount cards whose lane moved
     * @param repairedCount stranded requests the sweep satisfied
     * @param lapsedInboundCount inbound claims that are no longer supply
     * @param expiredExceptionCount acceptances whose period ran out
     */
    public record SweepResult(UUID runId, boolean completed, int variantCount,
                              int changedCardCount, int repairedCount, int lapsedInboundCount,
                              int expiredExceptionCount, int failedVariantCount) {
    }
}

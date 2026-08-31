package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.internal.config.AvailabilityWorkerProperties;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
import com.mimococo.marketops.shared.CorrelationId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the availability loop on a timer.
 *
 * <p>The bean does not exist unless an environment switched it on, which is why
 * a workstation or a test container never starts recalculating a portfolio by
 * simply running the application. That is a stronger guarantee than a check
 * inside a method somebody could later restructure.
 *
 * <p>The two timers are separate because the two failures are separate. A scan
 * that stops means new facts raise nothing; a sweep that stops means nothing
 * catches what the scan missed. Running them on one timer would hide the first
 * behind the second.
 */
@Component
@ConditionalOnProperty(prefix = "marketops.availability", name = "worker-enabled",
        havingValue = "true")
public class AvailabilityRecalculationScheduler {

    private static final Logger LOG =
            LoggerFactory.getLogger(AvailabilityRecalculationScheduler.class);

    /** How often accepted facts are turned into work, in milliseconds. */
    private static final long SCAN_INTERVAL_MILLIS = 30_000L;

    /** How often the portfolio is reconciled, in milliseconds. */
    private static final long SWEEP_INTERVAL_MILLIS = 3_600_000L;

    /** How long the process waits before its first scan, in milliseconds. */
    private static final long SCAN_INITIAL_DELAY_MILLIS = 20_000L;

    /** How long the process waits before its first sweep, in milliseconds. */
    private static final long SWEEP_INITIAL_DELAY_MILLIS = 120_000L;

    private final AvailabilityTriggerIngestionService ingestion;
    private final AvailabilityTargetedWorker targeted;
    private final AvailabilityReconciliationWorker reconciliation;
    private final AvailabilityOperationsHealth health;
    private final AvailabilityRecalculationRepository queue;
    private final AvailabilityWorkerProperties properties;

    AvailabilityRecalculationScheduler(AvailabilityTriggerIngestionService ingestion,
                                       AvailabilityTargetedWorker targeted,
                                       AvailabilityReconciliationWorker reconciliation,
                                       AvailabilityOperationsHealth health,
                                       AvailabilityRecalculationRepository queue,
                                       AvailabilityWorkerProperties properties) {
        this.ingestion = ingestion;
        this.targeted = targeted;
        this.reconciliation = reconciliation;
        this.health = health;
        this.queue = queue;
        this.properties = properties;
    }

    /** Read what was accepted and recalculate what it invalidated. */
    @Scheduled(initialDelay = SCAN_INITIAL_DELAY_MILLIS, fixedDelay = SCAN_INTERVAL_MILLIS)
    public void recalculateWhatChanged() {
        AvailabilityTriggerIngestionService.ScanResult scan =
                ingestion.scanOnce(properties.getFactsPerScan());
        int worked = targeted.runOnce(properties.getVariantsPerPass());
        if (scan.queued() > 0 || worked > 0) {
            LOG.atInfo()
                    .addKeyValue("event", "availability_targeted_pass_completed")
                    .addKeyValue("factsScanned", scan.scanned())
                    .addKeyValue("variantsQueued", scan.queued())
                    .addKeyValue("variantsRecalculated", worked)
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("A targeted availability pass completed");
        }
    }

    /** Reconcile every organization's whole portfolio, and report what it cost. */
    @Scheduled(initialDelay = SWEEP_INITIAL_DELAY_MILLIS, fixedRate = SWEEP_INTERVAL_MILLIS)
    public void reconcileEveryPortfolio() {
        for (UUID organizationId : queue.activeOrganizations()) {
            reconciliation.sweep(organizationId, "SCHEDULED")
                    .ifPresent(result -> LOG.atInfo()
                            .addKeyValue("event", "availability_reconciliation_completed")
                            .addKeyValue("runId", result.runId())
                            .addKeyValue("variantCount", result.variantCount())
                            .addKeyValue("changedCardCount", result.changedCardCount())
                            .addKeyValue("repairedCount", result.repairedCount())
                            .addKeyValue("correlationId", CorrelationId.current())
                            .log("A portfolio reconciliation completed"));
            reportIncidents(organizationId);
        }
    }

    /**
     * Say out loud what an operator would otherwise have to go looking for.
     *
     * <p>Logged at warning rather than counted quietly: a missed sweep or a
     * backlog past the response obligation means the queue an operator is
     * trusting is not current, and that is exactly the condition a silent
     * metric fails to convey.
     */
    private void reportIncidents(UUID organizationId) {
        AvailabilityOperationsHealth.LoopHealth state = health.health(organizationId);
        if (state.healthy()) {
            return;
        }
        LOG.atWarn()
                .addKeyValue("event", "availability_loop_incident")
                .addKeyValue("organizationId", organizationId)
                .addKeyValue("incidents", String.join(",", state.incidents()))
                .addKeyValue("pendingRequests", state.pendingRequests())
                .addKeyValue("oldestPendingSeconds", state.oldestPendingAge().toSeconds())
                .addKeyValue("correlationId", CorrelationId.current())
                .log("The availability loop is not meeting its obligations");
    }
}

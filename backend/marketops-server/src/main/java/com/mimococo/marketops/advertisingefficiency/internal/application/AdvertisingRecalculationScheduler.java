package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.config.AdvertisingWorkerProperties;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingRecalculationRepository;
import com.mimococo.marketops.shared.CorrelationId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The two timers.
 *
 * <p>They are separate on purpose, and so are their scheduling modes. The scan
 * uses a fixed delay because a slow pass should not pile up behind itself; the
 * sweep uses a fixed rate because its cadence is the Contract's hourly
 * obligation and must not drift with how long a sweep happens to take.
 *
 * <p>A scan that stops means new facts raise nothing. A sweep that stops means
 * nothing catches what the scan missed. They fail differently, so they are
 * observed differently.
 *
 * <p>The bean does not exist unless the loop is enabled. There is deliberately no
 * in-method switch: a disabled loop should look like a process with no timer.
 */
@Component
@ConditionalOnProperty(prefix = "marketops.advertising", name = "worker-enabled",
        havingValue = "true")
class AdvertisingRecalculationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(AdvertisingRecalculationScheduler.class);

    private final AdvertisingTargetedWorker targeted;
    private final AdvertisingReconciliationWorker reconciliation;
    private final AdvertisingRecalculationRepository queue;
    private final AdvertisingWorkerProperties properties;

    AdvertisingRecalculationScheduler(
            AdvertisingTargetedWorker targeted,
            AdvertisingReconciliationWorker reconciliation,
            AdvertisingRecalculationRepository queue,
            AdvertisingWorkerProperties properties) {
        this.targeted = targeted;
        this.reconciliation = reconciliation;
        this.queue = queue;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${marketops.advertising.scan-initial-delay:PT20S}",
            fixedDelayString = "${marketops.advertising.scan-interval:PT30S}")
    void recalculateWhatChanged() {
        int handled = targeted.runOnce(properties.getObjectsPerPass());
        if (handled > 0) {
            log.info("event=advertising_targeted_pass_completed handled={} correlationId={}",
                    handled, CorrelationId.current());
        }
    }

    @Scheduled(initialDelayString = "${marketops.advertising.sweep-initial-delay:PT2M}",
            fixedRateString = "${marketops.advertising.sweep-interval:PT1H}")
    void reconcileEveryPortfolio() {
        for (UUID organizationId : queue.activeOrganizations()) {
            reconciliation.sweep(organizationId, "SCHEDULED").ifPresent(result ->
                    log.info("event=advertising_reconciliation_completed organizationId={}"
                                    + " objects={} changed={} repaired={} failed={}"
                                    + " correlationId={}",
                            organizationId, result.objectCount(), result.changedCaseCount(),
                            result.repairedCount(), result.failedObjectCount(),
                            CorrelationId.current()));
        }
    }
}

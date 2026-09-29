package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.shared.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The collection timer. The bean does not exist unless it is switched on, and even then it collects
 * only stores whose Owner put scheduled collection in force. Passes are short: a run waiting for a
 * rate limit is left for a later pass instead of being slept on, so a fixed delay of a minute keeps
 * collection moving without holding the shared scheduling thread.
 */
@Component
@ConditionalOnProperty(prefix = "marketops.data-collection", name = "enabled", havingValue = "true")
class ScheduledCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledCollectionScheduler.class);

    private final ScheduledCollectionService collection;

    ScheduledCollectionScheduler(ScheduledCollectionService collection) {
        this.collection = collection;
    }

    @Scheduled(initialDelayString = "${marketops.data-collection.initial-delay:PT1M}",
            fixedDelayString = "${marketops.data-collection.interval:PT1M}")
    void collect() {
        // One identifier per pass, so every run, call and record of it can be found together.
        MDC.put(CorrelationId.LOG_CONTEXT_KEY, CorrelationId.generate());
        try {
            ScheduledCollectionService.PassSummary summary = collection.runPass();
            if (summary.executed() > 0 || summary.recalculated() > 0) {
                log.atInfo().addKeyValue("event", "scheduled_collection_pass")
                        .addKeyValue("runsExecuted", summary.executed())
                        .addKeyValue("recalculations", summary.recalculated())
                        .log("A scheduled collection pass did work");
            }
        } catch (RuntimeException failedPass) {
            // Every step is its own transaction; what completed stays, and the next pass carries on.
            log.atWarn().addKeyValue("event", "scheduled_collection_pass_failed")
                    .addKeyValue("failureType", failedPass.getClass().getSimpleName())
                    .log("A scheduled collection pass failed");
        } finally {
            MDC.remove(CorrelationId.LOG_CONTEXT_KEY);
        }
    }
}

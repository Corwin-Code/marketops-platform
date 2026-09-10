package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.listingconversion.internal.config.ListingConversionProperties;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.operationsworkflow.ListingExecutionJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the recalculation loop only where the environment says so; absent means off. */
@Component
@ConditionalOnProperty(prefix = "marketops.listing-conversion", name = "worker-enabled", havingValue = "true")
class ListingConversionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ListingConversionScheduler.class);

    private final RecalculationService recalculation;
    private final ListingConversionProperties properties;
    private final ListingExecutionJournal executionJournal;

    ListingConversionScheduler(RecalculationService recalculation, ListingConversionProperties properties,
                               ListingExecutionJournal executionJournal) {
        this.recalculation = recalculation;
        this.properties = properties;
        this.executionJournal = executionJournal;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    void drainQueue() {
        try {
            executionJournal.deliverPending(properties.getListingsPerPass());
        } catch (RuntimeException failedDelivery) {
            // The delivery transaction rolls back; the next pass can retry it.
            // An independent recalculation must still get its turn.
            log.warn("event=lc_execution_journal_delivery_failed failureType={} correlationId={}",
                    failedDelivery.getClass().getSimpleName(), CorrelationId.current());
        }
        int finished = recalculation.runOnce(properties.getListingsPerPass());
        if (finished > 0) {
            log.info("event=lc_recalculation_pass_completed finished={} correlationId={}", finished,
                    CorrelationId.current());
        }
    }
}

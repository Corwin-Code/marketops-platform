package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.listingconversion.internal.config.ListingConversionProperties;
import com.mimococo.marketops.shared.CorrelationId;
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

    ListingConversionScheduler(RecalculationService recalculation, ListingConversionProperties properties) {
        this.recalculation = recalculation;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    void drainQueue() {
        int finished = recalculation.runOnce(properties.getListingsPerPass());
        if (finished > 0) {
            log.info("event=lc_recalculation_pass_completed finished={} correlationId={}", finished,
                    CorrelationId.current());
        }
    }
}

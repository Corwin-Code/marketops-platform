package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.config.ListingDescriptionWriteProperties;
import com.mimococo.marketops.shared.CorrelationId;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the description worker only where the environment says so; absent means off. */
@Component
@ConditionalOnProperty(prefix = "marketops.listing-description-write", name = "worker-enabled",
        havingValue = "true")
class ListingDescriptionCommandScheduler {

    private static final Logger log = LoggerFactory.getLogger(ListingDescriptionCommandScheduler.class);

    private final ListingDescriptionCommandWorker worker;
    private final ListingDescriptionWriteProperties properties;
    private final Clock clock;

    ListingDescriptionCommandScheduler(ListingDescriptionCommandWorker worker,
                                       ListingDescriptionWriteProperties properties, Clock clock) {
        this.worker = worker;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 15_000)
    void advanceReadyCommands() {
        int advanced = worker.runOnce(clock.instant(), properties.getCommandsPerPass());
        if (advanced > 0) {
            log.info("event=lc_description_command_pass_completed advanced={} correlationId={}",
                    advanced, CorrelationId.current());
        }
    }
}

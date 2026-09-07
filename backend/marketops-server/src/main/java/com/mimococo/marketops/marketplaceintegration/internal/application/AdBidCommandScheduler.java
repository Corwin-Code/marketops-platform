package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.config.AdBidWriteProperties;
import com.mimococo.marketops.shared.CorrelationId;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The advertising write timer.
 *
 * <p>The bean exists only when the worker is enabled, which it is not by
 * default and is not in any shipped profile. That is one of several independent
 * reasons no advertising bid change can be transmitted; it is the weakest of
 * them, and it is here so the process is honest about what it is not doing
 * rather than so the others can be relaxed.
 */
@Component
@ConditionalOnProperty(prefix = "marketops.ad-bid-write", name = "worker-enabled",
        havingValue = "true")
class AdBidCommandScheduler {

    private static final Logger log = LoggerFactory.getLogger(AdBidCommandScheduler.class);

    private final AdBidCommandWorker worker;
    private final AdBidWriteProperties properties;
    private final Clock clock;

    AdBidCommandScheduler(AdBidCommandWorker worker, AdBidWriteProperties properties, Clock clock) {
        this.worker = worker;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 15_000)
    void advanceReadyCommands() {
        int advanced = worker.runOnce(clock.instant(), properties.getCommandsPerPass());
        if (advanced > 0) {
            log.info("event=ad_bid_command_pass_completed advanced={} correlationId={}",
                    advanced, CorrelationId.current());
        }
    }
}

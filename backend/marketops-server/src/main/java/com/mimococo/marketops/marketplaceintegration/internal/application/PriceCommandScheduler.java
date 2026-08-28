package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.config.PriceWriteProperties;
import com.mimococo.marketops.shared.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives price commands forward on a timer.
 *
 * <p>The bean does not exist unless an environment switched it on. That is the
 * only reason a workstation or a test container never changes a real price by
 * simply running the application, and it is a stronger guarantee than a check
 * inside a method somebody could later restructure.
 *
 * <p>One pass takes a bounded number of commands. An unbounded pass would let a
 * backlog occupy the process for as long as the backlog lasted, and a command
 * claimed by a worker that is busy elsewhere is a command nobody is executing.
 */
@Component
@ConditionalOnProperty(prefix = "marketops.price-write", name = "worker-enabled",
        havingValue = "true")
public class PriceCommandScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceCommandScheduler.class);

    /** How often a pass runs, in milliseconds. */
    private static final long PASS_INTERVAL_MILLIS = 15_000L;

    /** How long the process waits before its first pass, in milliseconds. */
    private static final long INITIAL_DELAY_MILLIS = 20_000L;

    private final PriceCommandWorker worker;
    private final PriceWriteProperties properties;

    PriceCommandScheduler(PriceCommandWorker worker, PriceWriteProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    /** Advance whatever is ready. */
    @Scheduled(initialDelay = INITIAL_DELAY_MILLIS, fixedDelay = PASS_INTERVAL_MILLIS)
    public void advanceReadyCommands() {
        int worked = worker.runOnce(properties.getCommandsPerPass());
        if (worked > 0) {
            log.atInfo()
                    .addKeyValue("event", "price_command_pass_completed")
                    .addKeyValue("commandsAdvanced", worked)
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("A price command pass completed");
        }
    }
}

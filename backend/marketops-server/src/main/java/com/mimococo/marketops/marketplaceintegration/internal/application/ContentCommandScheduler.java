package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.config.ContentWriteProperties;
import com.mimococo.marketops.shared.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives content commands forward on a timer (W2). The bean exists only where an environment
 * switched the content worker on.
 */
@Component
@ConditionalOnProperty(prefix = "marketops.content-write", name = "worker-enabled", havingValue = "true")
public class ContentCommandScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContentCommandScheduler.class);

    /** How often a pass runs, in milliseconds. */
    private static final long PASS_INTERVAL_MILLIS = 10_000L;

    /** How long the process waits before its first pass, in milliseconds. */
    private static final long INITIAL_DELAY_MILLIS = 25_000L;

    private final ContentCommandWorker worker;
    private final ContentWriteProperties properties;

    ContentCommandScheduler(ContentCommandWorker worker, ContentWriteProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    /** Advance whatever is due. */
    @Scheduled(initialDelay = INITIAL_DELAY_MILLIS, fixedDelay = PASS_INTERVAL_MILLIS)
    public void advanceDueCommands() {
        int worked = worker.runOnce(properties.getCommandsPerPass());
        if (worked > 0) {
            log.atInfo()
                    .addKeyValue("event", "content_command_pass_completed")
                    .addKeyValue("commandsAdvanced", worked)
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("A content command pass completed");
        }
    }
}

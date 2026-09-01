package com.mimococo.marketops.availabilityrisk.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much work one availability pass may take on.
 *
 * <p>Bounded on purpose. An unbounded pass lets a backlog occupy the process
 * for as long as the backlog lasts, and a request claimed by a worker that is
 * busy elsewhere is a request nobody is working.
 */
@ConfigurationProperties(prefix = "marketops.availability")
public class AvailabilityWorkerProperties {

    /** Whether the timers exist at all. */
    private boolean workerEnabled;

    /** Accepted facts read in one scan. */
    private int factsPerScan = 500;

    /** Recalculations claimed in one pass. */
    private int variantsPerPass = 50;

    /** How often the accepted-fact feed is scanned. */
    private Duration scanInterval = Duration.ofSeconds(30);

    /** How long a newly started process waits before its first fact scan. */
    private Duration scanInitialDelay = Duration.ofSeconds(20);

    /** How often every active portfolio is reconciled. */
    private Duration sweepInterval = Duration.ofHours(1);

    /** How long a newly started process waits before its first full sweep. */
    private Duration sweepInitialDelay = Duration.ofMinutes(2);

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public int getFactsPerScan() {
        return factsPerScan;
    }

    public void setFactsPerScan(int factsPerScan) {
        this.factsPerScan = factsPerScan;
    }

    public int getVariantsPerPass() {
        return variantsPerPass;
    }

    public void setVariantsPerPass(int variantsPerPass) {
        this.variantsPerPass = variantsPerPass;
    }

    public Duration getScanInterval() {
        return scanInterval;
    }

    public void setScanInterval(Duration scanInterval) {
        this.scanInterval = scanInterval;
    }

    public Duration getScanInitialDelay() {
        return scanInitialDelay;
    }

    public void setScanInitialDelay(Duration scanInitialDelay) {
        this.scanInitialDelay = scanInitialDelay;
    }

    public Duration getSweepInterval() {
        return sweepInterval;
    }

    public void setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval;
    }

    public Duration getSweepInitialDelay() {
        return sweepInitialDelay;
    }

    public void setSweepInitialDelay(Duration sweepInitialDelay) {
        this.sweepInitialDelay = sweepInitialDelay;
    }
}

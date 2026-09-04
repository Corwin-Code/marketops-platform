package com.mimococo.marketops.advertisingefficiency.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the advertising loop is scheduled.
 *
 * <p>{@code workerEnabled} defaults to false and gates the existence of the
 * scheduler bean rather than being checked inside it. A disabled loop is a
 * process with no timer, not a process with a timer that returns early — the
 * difference matters when somebody is trying to work out why nothing is
 * happening.
 */
@ConfigurationProperties(prefix = "marketops.advertising")
public class AdvertisingWorkerProperties {

    private boolean workerEnabled;
    private int objectsPerPass = 50;
    private Duration scanInterval = Duration.ofSeconds(30);
    private Duration scanInitialDelay = Duration.ofSeconds(20);
    private Duration sweepInterval = Duration.ofHours(1);
    private Duration sweepInitialDelay = Duration.ofMinutes(2);
    private int outcomesPerPass = 50;
    private Duration outcomeInterval = Duration.ofMinutes(15);
    private Duration outcomeInitialDelay = Duration.ofMinutes(3);

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public int getObjectsPerPass() {
        return objectsPerPass;
    }

    public void setObjectsPerPass(int objectsPerPass) {
        this.objectsPerPass = objectsPerPass;
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

    public int getOutcomesPerPass() {
        return outcomesPerPass;
    }

    public void setOutcomesPerPass(int outcomesPerPass) {
        this.outcomesPerPass = outcomesPerPass;
    }

    public Duration getOutcomeInterval() {
        return outcomeInterval;
    }

    public void setOutcomeInterval(Duration outcomeInterval) {
        this.outcomeInterval = outcomeInterval;
    }

    public Duration getOutcomeInitialDelay() {
        return outcomeInitialDelay;
    }

    public void setOutcomeInitialDelay(Duration outcomeInitialDelay) {
        this.outcomeInitialDelay = outcomeInitialDelay;
    }

    public void setSweepInitialDelay(Duration sweepInitialDelay) {
        this.sweepInitialDelay = sweepInitialDelay;
    }
}

package com.mimococo.marketops.marketplaceintegration.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the advertising write worker runs, and whether it runs at all.
 *
 * <p>{@code workerEnabled} defaults to false and gates the scheduler bean's
 * existence rather than being checked inside it. A process with no timer is a
 * different thing from a process whose timer returns early, and only one of them
 * is obvious to somebody trying to work out why nothing is happening.
 */
@ConfigurationProperties(prefix = "marketops.ad-bid-write")
public class AdBidWriteProperties {

    private boolean workerEnabled;

    @Min(1)
    @Max(100)
    private int commandsPerPass = 10;

    @Min(0)
    @Max(10)
    private int retryBudget = 3;

    @Min(30)
    @Max(900)
    private int leaseSeconds = 120;

    @Min(1)
    @Max(3600)
    private int retryDelaySeconds = 60;

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public int getCommandsPerPass() {
        return commandsPerPass;
    }

    public void setCommandsPerPass(int commandsPerPass) {
        this.commandsPerPass = commandsPerPass;
    }

    public int getRetryBudget() {
        return retryBudget;
    }

    public void setRetryBudget(int retryBudget) {
        this.retryBudget = retryBudget;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getRetryDelaySeconds() {
        return retryDelaySeconds;
    }

    public void setRetryDelaySeconds(int retryDelaySeconds) {
        this.retryDelaySeconds = retryDelaySeconds;
    }
}

package com.mimococo.marketops.marketplaceintegration.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The description worker's own bounds. Off unless an environment says otherwise. */
@ConfigurationProperties(prefix = "marketops.listing-description-write")
public class ListingDescriptionWriteProperties {

    private boolean workerEnabled;

    @Min(1)
    @Max(100)
    private int commandsPerPass = 10;

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

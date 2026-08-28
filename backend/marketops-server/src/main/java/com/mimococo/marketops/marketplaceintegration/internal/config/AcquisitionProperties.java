package com.mimococo.marketops.marketplaceintegration.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How this process runs acquisition work.
 *
 * <p>The scheduler is off by default and every environment that wants it has to
 * say so. A worker that started itself wherever the application happened to run
 * would reach marketplaces from a workstation and from a test container, so the
 * default is the safe one and enabling it is a deployment decision.
 *
 * <p>The lease and the retry budget are bounds rather than preferences. A lease
 * long enough to outlive a crash would leave a job unclaimable for that long,
 * and a retry budget without an end turns a permanent failure into permanent
 * traffic against a marketplace.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.acquisition")
public final class AcquisitionProperties {

    @NotNull
    private Boolean schedulerEnabled = Boolean.FALSE;

    @NotNull
    private Duration leaseDuration = Duration.ofMinutes(5);

    @Min(1)
    @Max(50)
    private int maximumCallsPerRun = 20;

    @Min(0)
    @Max(10)
    private int retryBudget = 3;

    @NotNull
    private Duration retryDelay = Duration.ofMinutes(2);

    @Min(1)
    @Max(100)
    private int runsPerSchedulerPass = 5;

    /** Whether this process claims and executes acquisition runs. */
    public Boolean getSchedulerEnabled() {
        return schedulerEnabled;
    }

    /** Bind the scheduler switch. */
    public void setSchedulerEnabled(Boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    /** How long a claimed run is held before another worker may take it over. */
    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    /** Bind the lease duration. */
    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    /** How many source calls one run may make before it yields. */
    public int getMaximumCallsPerRun() {
        return maximumCallsPerRun;
    }

    /** Bind the per-run call ceiling. */
    public void setMaximumCallsPerRun(int maximumCallsPerRun) {
        this.maximumCallsPerRun = maximumCallsPerRun;
    }

    /** How many times a failing run is retried before it fails terminally. */
    public int getRetryBudget() {
        return retryBudget;
    }

    /** Bind the retry budget. */
    public void setRetryBudget(int retryBudget) {
        this.retryBudget = retryBudget;
    }

    /** How long a run waits before it becomes claimable again. */
    public Duration getRetryDelay() {
        return retryDelay;
    }

    /** Bind the retry delay. */
    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    /** How many runs one scheduler pass attempts to claim. */
    public int getRunsPerSchedulerPass() {
        return runsPerSchedulerPass;
    }

    /** Bind the scheduler pass size. */
    public void setRunsPerSchedulerPass(int runsPerSchedulerPass) {
        this.runsPerSchedulerPass = runsPerSchedulerPass;
    }
}

package com.mimococo.marketops.operationsworkflow.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The collection scheduler: whether its timer exists, when each day's collection slot starts (UTC)
 * and how much one pass may do. Whether a store is collected at all is the Owner's policy, not this.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.data-collection")
public class ScheduledCollectionProperties {

    private boolean enabled;

    /** When each UTC day's collection slot starts. */
    @NotNull
    private LocalTime dailyAt = LocalTime.of(0, 10);

    /** How many runs one pass may execute across all stores; the rest wait for the next pass. */
    @Min(1)
    @Max(20)
    private int runsPerPass = 4;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalTime getDailyAt() {
        return dailyAt;
    }

    public void setDailyAt(LocalTime dailyAt) {
        this.dailyAt = dailyAt;
    }

    public int getRunsPerPass() {
        return runsPerPass;
    }

    public void setRunsPerPass(int runsPerPass) {
        this.runsPerPass = runsPerPass;
    }
}

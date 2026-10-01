package com.mimococo.marketops.marketplaceintegration.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How this process carries out listing title and description changes (W2).
 *
 * <p>The worker is off by default: the absence of its bean, not a check inside it, keeps a
 * workstation or a test container from writing to a real card. Turning it on does not turn
 * writes on: every command still passes the content write gate and the deployment's
 * production-write switch.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.content-write")
public final class ContentWriteProperties {

    @NotNull
    private Boolean workerEnabled = Boolean.FALSE;

    @Min(1)
    @Max(20)
    private int commandsPerPass = 5;

    /** Whether this process claims and carries out content commands. */
    public Boolean getWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(Boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    /** How many commands one pass takes. */
    public int getCommandsPerPass() {
        return commandsPerPass;
    }

    public void setCommandsPerPass(int commandsPerPass) {
        this.commandsPerPass = commandsPerPass;
    }
}

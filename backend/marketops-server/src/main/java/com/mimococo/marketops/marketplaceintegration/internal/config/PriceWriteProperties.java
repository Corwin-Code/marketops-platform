package com.mimococo.marketops.marketplaceintegration.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How this process executes price commands.
 *
 * <p>The worker is off by default and every environment that wants it has to say
 * so. A worker that started itself wherever the application happened to run
 * would change real marketplace prices from a workstation and from a test
 * container, so the absence of the bean rather than a check inside it is what
 * makes the default safe.
 *
 * <p>Enabling this switch does not enable writes. Every command still passes the
 * write gate, which requires a verified capability, both switches explicitly on,
 * an allowlisted entity, a live authorization and a deterministic guardrail
 * pass. This only decides whether this process is one of the ones that tries.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.price-write")
public final class PriceWriteProperties {

    @NotNull
    private Boolean workerEnabled = Boolean.FALSE;

    @Min(1)
    @Max(50)
    private int commandsPerPass = 5;

    @Min(0)
    @Max(10)
    private int retryBudget = 3;

    /** Whether this process claims and executes price commands. */
    public Boolean getWorkerEnabled() {
        return workerEnabled;
    }

    /** Bind the worker switch. */
    public void setWorkerEnabled(Boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    /** How many commands one pass claims before it yields. */
    public int getCommandsPerPass() {
        return commandsPerPass;
    }

    /** Bind the per-pass command ceiling. */
    public void setCommandsPerPass(int commandsPerPass) {
        this.commandsPerPass = commandsPerPass;
    }

    /** How many retriable failures a new command may absorb. */
    public int getRetryBudget() {
        return retryBudget;
    }

    /** Bind the retry budget. */
    public void setRetryBudget(int retryBudget) {
        this.retryBudget = retryBudget;
    }
}

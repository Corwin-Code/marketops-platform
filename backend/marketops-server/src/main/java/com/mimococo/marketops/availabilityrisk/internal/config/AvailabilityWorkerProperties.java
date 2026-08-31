package com.mimococo.marketops.availabilityrisk.internal.config;

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
}

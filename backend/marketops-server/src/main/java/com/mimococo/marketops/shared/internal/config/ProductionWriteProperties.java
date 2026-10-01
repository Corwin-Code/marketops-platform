package com.mimococo.marketops.shared.internal.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The deployment's production-write switch.
 *
 * <p>Off unless an environment turns it on. It is the outermost of the reasons nothing changes on a
 * marketplace: while it is off no write-capability flag can be enabled, from the registry or from the
 * console, and the price worker sends nothing that changes a price. Turning it on enables nothing by
 * itself: every write still needs a verified capability, a store marked available, the database
 * switches, the allowlist, an approval and the write gate. The Owner authorized the write capabilities
 * on 2026-10-01; only the local profile turns it on.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.production-writes")
public final class ProductionWriteProperties {

    @NotNull
    private Boolean enabled;

    /** Whether production writes are enabled for this deployment. */
    public Boolean getEnabled() {
        return enabled;
    }

    /** Bind the production-write switch. */
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}

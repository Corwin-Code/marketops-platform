package com.mimococo.marketops.shared.internal.config;

import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The global production-write gate binding.
 *
 * <p>The value must be present and must be {@code false}. No controlled-write
 * capability exists, so a configuration that claims production writes are
 * enabled is invalid and fails application startup at binding time. This makes
 * the gate unoverridable: neither a profile nor any metadata flag can produce a
 * running process that believes production writes are on.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.production-writes")
public final class ProductionWriteProperties {

    @NotNull
    @AssertFalse(message = "production writes cannot be enabled; no controlled-write capability exists")
    private Boolean enabled;

    /** Whether production writes are enabled; always {@code false} when bound. */
    public Boolean getEnabled() {
        return enabled;
    }

    /** Bind the production-write gate. */
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}

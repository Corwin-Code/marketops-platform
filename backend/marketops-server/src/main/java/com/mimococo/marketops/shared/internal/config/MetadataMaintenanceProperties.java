package com.mimococo.marketops.shared.internal.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Whether this environment accepts metadata maintenance writes.
 *
 * <p>The base configuration sets the value to {@code false}, so an environment
 * that has not explicitly opted in refuses every maintenance mutation. The
 * property is mandatory: a profile that removed it would fail startup rather
 * than fall into an implicit default.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.metadata-maintenance")
public final class MetadataMaintenanceProperties {

    @NotNull
    private Boolean writeEnabled;

    /** Whether maintenance mutations are accepted. */
    public Boolean getWriteEnabled() {
        return writeEnabled;
    }

    /** Bind the maintenance write switch. */
    public void setWriteEnabled(Boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }
}

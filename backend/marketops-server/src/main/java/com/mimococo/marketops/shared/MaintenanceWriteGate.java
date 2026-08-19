package com.mimococo.marketops.shared;

import com.mimococo.marketops.shared.internal.config.MetadataMaintenanceProperties;
import org.springframework.stereotype.Component;

/**
 * Fail-closed switch for the metadata maintenance write surface.
 *
 * <p>The base configuration disables writes, so an environment that has not
 * explicitly opted in refuses every mutation command while queries stay
 * available. The gate is one bean so the admin boundary and its tests consult
 * the same value the environment configured.
 *
 * <p>This switch is an environment boundary, not access control: it decides
 * whether this process accepts maintenance writes at all, never who may issue
 * them.
 */
@Component
public class MaintenanceWriteGate {

    private final boolean writeEnabled;

    public MaintenanceWriteGate(MetadataMaintenanceProperties properties) {
        this.writeEnabled = properties.getWriteEnabled();
    }

    /** Whether this environment accepts metadata maintenance writes. */
    public boolean writeEnabled() {
        return writeEnabled;
    }
}

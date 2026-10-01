package com.mimococo.marketops.shared;

import com.mimococo.marketops.shared.internal.config.ProductionWriteProperties;
import org.springframework.stereotype.Component;

/**
 * The deployment's production-write switch as seen by application code.
 *
 * <p>Off unless the environment turns it on ({@code marketops.production-writes.enabled}). While it is
 * off, consumers refuse anything that would enable or perform a platform write: enabling a
 * write-capability flag from the registry or the console, and a price worker's write or restore. It is
 * one gate among several, never a substitute for the database's write gate.
 */
@Component
public class ProductionWritePolicy {

    private final boolean enabled;

    public ProductionWritePolicy(ProductionWriteProperties properties) {
        this.enabled = properties.getEnabled();
    }

    /** Whether platform production writes are enabled for this process. */
    public boolean productionWritesEnabled() {
        return enabled;
    }
}

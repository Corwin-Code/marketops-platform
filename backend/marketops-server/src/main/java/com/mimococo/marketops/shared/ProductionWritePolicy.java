package com.mimococo.marketops.shared;

import com.mimococo.marketops.shared.internal.config.ProductionWriteProperties;
import org.springframework.stereotype.Component;

/**
 * The global production-write gate as seen by application code.
 *
 * <p>Production writes are disabled for the whole product. The binding contract
 * behind this policy fails application startup when the property is configured
 * {@code true}, because no controlled-write capability exists that could make
 * such a configuration legitimate; a metadata flag therefore has nothing it
 * could override. Consumers use this policy to refuse any transition that
 * would represent an enabled platform write.
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

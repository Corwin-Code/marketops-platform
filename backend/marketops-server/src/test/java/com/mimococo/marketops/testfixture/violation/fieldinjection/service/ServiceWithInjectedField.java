package com.mimococo.marketops.testfixture.violation.fieldinjection.service;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A collaborator supplied into a field rather than through the constructor.
 *
 * <p>This is the arrangement the injection rule exists to reject: the dependency
 * is invisible in the signature and cannot be supplied without a container.
 */
public final class ServiceWithInjectedField {

    @Autowired
    private Clock clock;

    /** Return the configured zone. */
    public String zone() {
        return clock.getZone().getId();
    }
}

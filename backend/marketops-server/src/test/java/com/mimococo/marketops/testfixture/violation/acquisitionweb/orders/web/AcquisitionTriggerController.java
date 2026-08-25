package com.mimococo.marketops.testfixture.violation.acquisitionweb.orders.web;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * A web controller holding the acquisition doorway.
 *
 * <p>This is the arrangement the no-public-surface rule exists to reject: a
 * request thread that can reach the port turns any caller of the route into an
 * unscheduled, unleased, unfenced acquisition authority.
 *
 * <p>The profile guard keeps the fixture out of every application context: the
 * class exists to be rejected by a rule, not to be instantiated by a container
 * whose component scan can see test sources.
 */
@Profile("architecture-fixture")
@RestController
public class AcquisitionTriggerController {

    private final AcquisitionPort acquisition;

    public AcquisitionTriggerController(AcquisitionPort acquisition) {
        this.acquisition = acquisition;
    }

    /** Report which doorway this controller would use. */
    public String doorway() {
        return acquisition.getClass().getName();
    }
}

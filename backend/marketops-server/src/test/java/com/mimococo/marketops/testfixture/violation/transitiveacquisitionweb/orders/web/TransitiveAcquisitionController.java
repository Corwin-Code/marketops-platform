package com.mimococo.marketops.testfixture.violation.transitiveacquisitionweb.orders.web;

import com.mimococo.marketops.testfixture.violation.transitiveacquisitionweb.orders.application.AcquisitionBridgeService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately reaches the acquisition gateway through an intermediate service. */
@Profile("architecture-fixture")
@RestController
public final class TransitiveAcquisitionController {

    private final AcquisitionBridgeService bridge;

    public TransitiveAcquisitionController(AcquisitionBridgeService bridge) {
        this.bridge = bridge;
    }

    /** Keep the controller-to-service dependency visible to bytecode analysis. */
    public String doorway() {
        return bridge.gatewayType();
    }
}

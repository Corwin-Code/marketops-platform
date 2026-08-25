package com.mimococo.marketops.testfixture.violation.transitiveacquisitionweb.orders.application;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.JdbcAuthorizedAcquisitionGateway;

/** Deliberately bridges a request-facing class to the acquisition gateway. */
public final class AcquisitionBridgeService {

    private final JdbcAuthorizedAcquisitionGateway gateway;

    public AcquisitionBridgeService(JdbcAuthorizedAcquisitionGateway gateway) {
        this.gateway = gateway;
    }

    /** Keep the forbidden second dependency edge visible to bytecode analysis. */
    public String gatewayType() {
        return gateway.getClass().getName();
    }
}

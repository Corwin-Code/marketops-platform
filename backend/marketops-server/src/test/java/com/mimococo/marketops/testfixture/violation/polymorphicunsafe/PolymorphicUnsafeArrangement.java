package com.mimococo.marketops.testfixture.violation.polymorphicunsafe;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.JdbcAuthorizedAcquisitionGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

interface AcquisitionUseCase {

    void acquire();
}

final class GatewayBackedAcquisitionService implements AcquisitionUseCase {

    private final JdbcAuthorizedAcquisitionGateway gateway;

    GatewayBackedAcquisitionService(JdbcAuthorizedAcquisitionGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void acquire() {
        gateway.getClass();
    }
}

@Profile("architecture-fixture")
@RestController
final class PolymorphicAcquisitionController {

    private final AcquisitionUseCase useCase;

    PolymorphicAcquisitionController(AcquisitionUseCase useCase) {
        this.useCase = useCase;
    }

    void acquire() {
        useCase.acquire();
    }
}

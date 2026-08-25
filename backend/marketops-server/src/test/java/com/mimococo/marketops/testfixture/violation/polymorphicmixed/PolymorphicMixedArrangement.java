package com.mimococo.marketops.testfixture.violation.polymorphicmixed;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.JdbcAuthorizedAcquisitionGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

interface MixedAcquisitionUseCase {

    void acquire();
}

final class SafeAcquisitionService implements MixedAcquisitionUseCase {

    @Override
    public void acquire() {
    }
}

final class UnsafeAcquisitionService implements MixedAcquisitionUseCase {

    private final JdbcAuthorizedAcquisitionGateway gateway;

    UnsafeAcquisitionService(JdbcAuthorizedAcquisitionGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void acquire() {
        gateway.getClass();
    }
}

@Profile("architecture-fixture")
@RestController
final class MixedAcquisitionController {

    private final MixedAcquisitionUseCase useCase;

    MixedAcquisitionController(MixedAcquisitionUseCase useCase) {
        this.useCase = useCase;
    }

    void acquire() {
        useCase.acquire();
    }
}

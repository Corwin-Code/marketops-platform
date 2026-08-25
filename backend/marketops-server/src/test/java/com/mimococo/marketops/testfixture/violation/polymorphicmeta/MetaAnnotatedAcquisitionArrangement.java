package com.mimococo.marketops.testfixture.violation.polymorphicmeta;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.JdbcAuthorizedAcquisitionGateway;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@RestController
@interface InternalRestController {
}

abstract class MetaAcquisitionUseCase {

    abstract void acquire();
}

final class MetaGatewayBackedService extends MetaAcquisitionUseCase {

    private final JdbcAuthorizedAcquisitionGateway gateway;

    MetaGatewayBackedService(JdbcAuthorizedAcquisitionGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    void acquire() {
        gateway.getClass();
    }
}

@Profile("architecture-fixture")
@InternalRestController
final class MetaAnnotatedAcquisitionController {

    private final MetaAcquisitionUseCase useCase;

    MetaAnnotatedAcquisitionController(MetaAcquisitionUseCase useCase) {
        this.useCase = useCase;
    }

    void acquire() {
        useCase.acquire();
    }
}

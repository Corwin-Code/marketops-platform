package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately makes an internal executor reachable from a request controller. */
@Profile("architecture-fixture")
@RestController
public final class ControllerViaExecutorFixture {

    private final AuthorizedAcquisitionExecutor executor;

    ControllerViaExecutorFixture(AuthorizedAcquisitionExecutor executor) {
        this.executor = executor;
    }

    /** Keep the forbidden dependency observable to bytecode analysis. */
    String executorType() {
        return executor.getClass().getName();
    }
}

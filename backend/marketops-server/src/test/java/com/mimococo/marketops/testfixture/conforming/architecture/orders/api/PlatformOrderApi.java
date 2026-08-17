package com.mimococo.marketops.testfixture.conforming.architecture.orders.api;

import com.mimococo.marketops.testfixture.conforming.architecture.orders.domain.Order;
import com.mimococo.marketops.testfixture.conforming.architecture.shared.PlatformIdentifier;
import java.util.List;

/** Named module interface whose signatures contain only platform-owned types. */
public final class PlatformOrderApi {

    private final PlatformIdentifier identifier;

    public PlatformOrderApi(PlatformIdentifier identifier) {
        this.identifier = identifier;
    }

    public List<Order> orders() {
        return List.of(new Order(identifier));
    }
}

package com.mimococo.marketops.testfixture.conforming.architecture.orders.port;

import com.mimococo.marketops.testfixture.conforming.architecture.orders.domain.Order;
import com.mimococo.marketops.testfixture.conforming.architecture.shared.PlatformIdentifier;
import java.util.Optional;

/** Inward-facing port expressed entirely in platform-owned types. */
public interface OrderRepository {
    Optional<Order> find(PlatformIdentifier identifier);
}

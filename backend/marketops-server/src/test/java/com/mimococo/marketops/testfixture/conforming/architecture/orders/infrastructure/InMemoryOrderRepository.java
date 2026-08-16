package com.mimococo.marketops.testfixture.conforming.architecture.orders.infrastructure;

import com.mimococo.marketops.testfixture.conforming.architecture.orders.domain.Order;
import com.mimococo.marketops.testfixture.conforming.architecture.orders.port.OrderRepository;
import com.mimococo.marketops.testfixture.conforming.architecture.shared.PlatformIdentifier;
import java.util.Optional;

/** Infrastructure implementation that correctly points inward to port and domain. */
public class InMemoryOrderRepository implements OrderRepository {
    @Override
    public Optional<Order> find(PlatformIdentifier identifier) {
        return Optional.of(new Order(identifier));
    }
}

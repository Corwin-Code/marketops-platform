package com.mimococo.marketops.testfixture.conforming.architecture.orders.application;

import com.mimococo.marketops.testfixture.conforming.architecture.orders.domain.Order;
import com.mimococo.marketops.testfixture.conforming.architecture.orders.port.OrderRepository;
import com.mimococo.marketops.testfixture.conforming.architecture.shared.PlatformIdentifier;
import java.util.Optional;

/** Application service depending on a port and domain types, never its implementation. */
public final class LoadOrder {
    private final OrderRepository repository;

    public LoadOrder(OrderRepository repository) {
        this.repository = repository;
    }

    public Optional<Order> execute(PlatformIdentifier identifier) {
        return repository.find(identifier);
    }
}

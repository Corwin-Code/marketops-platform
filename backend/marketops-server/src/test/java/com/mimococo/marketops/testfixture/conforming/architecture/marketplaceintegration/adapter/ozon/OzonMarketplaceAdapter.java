package com.mimococo.marketops.testfixture.conforming.architecture.marketplaceintegration.adapter.ozon;

import com.mimococo.marketops.testfixture.conforming.architecture.orders.application.LoadOrder;
import com.mimococo.marketops.testfixture.conforming.architecture.orders.domain.Order;
import com.mimococo.marketops.testfixture.conforming.architecture.orders.port.OrderRepository;
import com.mimococo.marketops.testfixture.conforming.architecture.shared.PlatformIdentifier;
import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkOrder;
import java.util.Optional;

/**
 * Valid anti-corruption adapter: the SDK terminates here and dependencies point
 * inward to application, port and domain contracts.
 */
public final class OzonMarketplaceAdapter {
    private final LoadOrder loadOrder;
    private final OrderRepository repository;

    public OzonMarketplaceAdapter(LoadOrder loadOrder, OrderRepository repository) {
        this.loadOrder = loadOrder;
        this.repository = repository;
    }

    public Optional<Order> translate(OzonSdkOrder vendorOrder) {
        PlatformIdentifier identifier = new PlatformIdentifier(vendorOrder.vendorIdentifier());
        return loadOrder.execute(identifier).or(() -> repository.find(identifier));
    }
}

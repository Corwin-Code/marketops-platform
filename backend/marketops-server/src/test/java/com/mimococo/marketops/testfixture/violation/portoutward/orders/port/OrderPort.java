package com.mimococo.marketops.testfixture.violation.portoutward.orders.port;

import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkOrder;
import com.mimococo.marketops.testfixture.violation.portoutward.orders.adapter.OrderAdapter;
import com.mimococo.marketops.testfixture.violation.portoutward.orders.infrastructure.OrderStorage;

/** Deliberately exposes concrete implementations from a port contract. */
public interface OrderPort {
    OrderAdapter adapterFor(OrderStorage storage, OzonSdkOrder vendorOrder);
}

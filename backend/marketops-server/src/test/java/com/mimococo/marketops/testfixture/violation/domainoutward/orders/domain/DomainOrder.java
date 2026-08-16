package com.mimococo.marketops.testfixture.violation.domainoutward.orders.domain;

import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkOrder;
import com.mimococo.marketops.testfixture.violation.domainoutward.orders.adapter.OrderAdapter;
import com.mimococo.marketops.testfixture.violation.domainoutward.orders.infrastructure.OrderStorage;

/** Deliberately violates all three domain-outward prohibitions. */
public record DomainOrder(OrderAdapter adapter, OrderStorage storage, OzonSdkOrder vendorOrder) {
}

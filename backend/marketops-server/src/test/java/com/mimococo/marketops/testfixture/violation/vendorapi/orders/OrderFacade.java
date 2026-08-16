package com.mimococo.marketops.testfixture.violation.vendorapi.orders;

import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkOrder;

/** Deliberately publishes a vendor type from a module's base package. */
public class OrderFacade {
    public OzonSdkOrder publish(OzonSdkOrder order) {
        return order;
    }
}

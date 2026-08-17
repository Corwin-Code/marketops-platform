package com.mimococo.marketops.testfixture.violation.vendorapi.orders.domain;

import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkOrder;

/** Deliberately places a vendor type in a domain signature. */
public record DomainOffer(OzonSdkOrder vendorOrder) {
}

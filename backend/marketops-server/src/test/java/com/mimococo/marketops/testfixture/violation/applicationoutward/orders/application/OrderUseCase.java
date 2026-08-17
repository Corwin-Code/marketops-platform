package com.mimococo.marketops.testfixture.violation.applicationoutward.orders.application;

import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkOrder;
import com.mimococo.marketops.testfixture.violation.applicationoutward.marketplaceintegration.adapter.ozon.OzonOrderAdapter;

/** Deliberately makes an application service depend on concrete vendor mechanisms. */
public record OrderUseCase(OzonOrderAdapter adapter, OzonSdkOrder vendorOrder) {
}

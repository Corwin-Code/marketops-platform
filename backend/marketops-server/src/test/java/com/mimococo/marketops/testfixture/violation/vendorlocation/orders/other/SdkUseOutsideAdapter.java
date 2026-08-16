package com.mimococo.marketops.testfixture.violation.vendorlocation.orders.other;

import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkOrder;

/** Deliberate vendor dependency outside marketplaceintegration.adapter.&lt;platform&gt;. */
public record SdkUseOutsideAdapter(OzonSdkOrder order) {
}

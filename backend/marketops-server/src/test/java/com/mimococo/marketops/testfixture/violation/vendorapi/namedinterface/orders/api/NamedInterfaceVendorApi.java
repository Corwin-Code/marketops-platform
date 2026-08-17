package com.mimococo.marketops.testfixture.violation.vendorapi.namedinterface.orders.api;

import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkBase;
import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkContract;
import com.mimococo.marketops.testfixture.vendorsdk.ozon.OzonSdkOrder;
import java.util.List;

/** Deliberately exposes vendor types through every supported API signature shape. */
public class NamedInterfaceVendorApi extends OzonSdkBase implements OzonSdkContract {

    public OzonSdkOrder vendorField;

    protected OzonSdkOrder protectedVendorField;

    public NamedInterfaceVendorApi(OzonSdkOrder vendorOrder) {
        this.vendorField = vendorOrder;
        this.protectedVendorField = vendorOrder;
    }

    public OzonSdkOrder returnOrder() {
        return vendorField;
    }

    public void acceptOrder(OzonSdkOrder vendorOrder) {
        this.vendorField = vendorOrder;
    }

    public List<OzonSdkOrder> genericOrders() {
        return List.of(vendorField);
    }
}

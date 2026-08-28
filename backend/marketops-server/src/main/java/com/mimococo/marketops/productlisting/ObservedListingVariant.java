package com.mimococo.marketops.productlisting;

import java.util.Objects;

/**
 * One listing variant as a source reported it.
 *
 * @param nativeVariantKey the marketplace's own variant identifier
 * @param nativeSkuKey the seller SKU the marketplace holds, or {@code null}
 * @param nativeBarcode the barcode the marketplace holds, or {@code null}
 * @param nativeColorLabel colour as the marketplace states it, or {@code null}
 * @param nativeSizeLabel size as the marketplace states it, or {@code null}
 * @param nativeStatus the marketplace's own status word, or {@code null}
 */
public record ObservedListingVariant(
        String nativeVariantKey,
        String nativeSkuKey,
        String nativeBarcode,
        String nativeColorLabel,
        String nativeSizeLabel,
        String nativeStatus) {

    public ObservedListingVariant {
        Objects.requireNonNull(nativeVariantKey, "nativeVariantKey");
    }
}

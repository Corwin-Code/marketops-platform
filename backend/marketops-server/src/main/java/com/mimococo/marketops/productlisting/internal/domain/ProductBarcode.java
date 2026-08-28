package com.mimococo.marketops.productlisting.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A barcode attached to an internal variant.
 *
 * <p>A live duplicate across two variants is refused, because a barcode is the
 * strongest automatic mapping signal and a duplicate would silently make the
 * matcher choose one of two products.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param productVariantId variant the barcode belongs to
 * @param barcodeType symbology, or {@code UNKNOWN}
 * @param barcodeValue the barcode itself
 * @param status whether the barcode is live
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record ProductBarcode(
        UUID id,
        UUID organizationId,
        UUID productVariantId,
        BarcodeType barcodeType,
        String barcodeValue,
        BarcodeStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

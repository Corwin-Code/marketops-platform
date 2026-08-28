package com.mimococo.marketops.productlisting.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The variant level of a platform listing, which is what a price and a stock
 * figure actually attach to.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param platformListingId listing the variant belongs to
 * @param nativeVariantKey the marketplace's own variant identifier, verbatim
 * @param nativeSkuKey the seller SKU the marketplace holds, or {@code null}
 * @param nativeBarcode the barcode the marketplace holds, or {@code null}
 * @param nativeColorLabel colour as the marketplace states it, or {@code null}
 * @param nativeSizeLabel size as the marketplace states it, or {@code null}
 * @param nativeStatus the marketplace's own status word, or {@code null}
 * @param firstSeenAt first observation
 * @param lastSeenAt most recent observation
 * @param status whether the variant is still observed
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record PlatformListingVariant(
        UUID id,
        UUID organizationId,
        UUID platformListingId,
        String nativeVariantKey,
        String nativeSkuKey,
        String nativeBarcode,
        String nativeColorLabel,
        String nativeSizeLabel,
        String nativeStatus,
        Instant firstSeenAt,
        Instant lastSeenAt,
        ObservationLifecycle status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

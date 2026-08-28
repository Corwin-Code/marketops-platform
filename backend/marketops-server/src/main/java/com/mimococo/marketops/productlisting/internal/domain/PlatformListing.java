package com.mimococo.marketops.productlisting.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What one marketplace publishes on one store, kept in the marketplace's own
 * identifiers.
 *
 * <p>The native keys are opaque and are never rewritten to look like internal
 * identity. That separation is what lets a mapping mistake be corrected without
 * making it indistinguishable from a change on the platform.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param storeId store the listing appears on
 * @param marketplaceAccountId account the store belongs to
 * @param platformCode marketplace the listing lives on
 * @param nativeListingKey the marketplace's own identifier, verbatim
 * @param nativeProductKey the marketplace's product identifier, or {@code null}
 * @param title published title, or {@code null}
 * @param nativeStatus the marketplace's own status word, or {@code null}
 * @param firstSeenAt first observation
 * @param lastSeenAt most recent observation
 * @param status whether the listing is still observed
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record PlatformListing(
        UUID id,
        UUID organizationId,
        UUID storeId,
        UUID marketplaceAccountId,
        String platformCode,
        String nativeListingKey,
        String nativeProductKey,
        String title,
        String nativeStatus,
        Instant firstSeenAt,
        Instant lastSeenAt,
        ObservationLifecycle status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

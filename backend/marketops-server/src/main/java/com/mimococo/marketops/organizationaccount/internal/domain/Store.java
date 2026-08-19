package com.mimococo.marketops.organizationaccount.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Store owned by a marketplace account.
 *
 * <p>Timezone and currency are operator-recorded facts validated against the
 * runtime registries; while unrecorded they stay {@code null} and every reader
 * presents them as unknown rather than substituting a default.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param marketplaceAccountId owning account
 * @param code business code, unique inside the organization
 * @param displayName operator-facing name
 * @param nativeStoreKey platform-native identifier, or {@code null}
 * @param timezone IANA zone, or {@code null}
 * @param currencyCode ISO 4217 code, or {@code null}
 * @param status lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record Store(
        UUID id,
        UUID organizationId,
        UUID marketplaceAccountId,
        String code,
        String displayName,
        String nativeStoreKey,
        String timezone,
        String currencyCode,
        EntityStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

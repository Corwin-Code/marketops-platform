package com.mimococo.marketops.organizationaccount.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Marketplace account owned by a legal entity on one platform.
 *
 * <p>The native account key mirrors the platform's own identifier opaquely: it
 * is stored exactly as supplied and its structure is never interpreted. A
 * legal entity may hold any number of accounts, and nothing here constrains
 * how many stores an account carries.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param legalEntityId owning legal entity
 * @param platformCode marketplace platform
 * @param code business code, unique inside the organization
 * @param displayName operator-facing name
 * @param nativeAccountKey platform-native identifier, or {@code null}
 * @param status lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record MarketplaceAccount(
        UUID id,
        UUID organizationId,
        UUID legalEntityId,
        String platformCode,
        String code,
        String displayName,
        String nativeAccountKey,
        EntityStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Membership of one store in a store-set credential's declared scope.
 *
 * <p>The row carries the account key so both composite foreign keys can pin
 * credential and store to the same marketplace account; a cross-account scope
 * row is unrepresentable in the schema.
 *
 * @param id identifier
 * @param credentialId credential the scope row belongs to
 * @param marketplaceAccountId account shared by the credential and the store
 * @param storeId store covered while the row is active
 * @param status recorded lifecycle status
 * @param reason reason recorded with withdrawal, or {@code null} while active
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record CredentialStoreScope(
        UUID id,
        UUID credentialId,
        UUID marketplaceAccountId,
        UUID storeId,
        StoreScopeStatus status,
        String reason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

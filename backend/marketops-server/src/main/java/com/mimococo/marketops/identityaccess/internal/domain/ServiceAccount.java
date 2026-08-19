package com.mimococo.marketops.identityaccess.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Non-human subject with one stated purpose and a mandatory expiry.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param code business code, unique inside the organization
 * @param displayName operator-facing name
 * @param purpose single stated purpose of the account
 * @param ownerLabel responsible owner or team label
 * @param expiresAt mandatory expiry evaluated against the clock
 * @param status recorded lifecycle status
 * @param disabledReason reason recorded with deactivation, or {@code null}
 * @param lastUsedAt last recorded use, or {@code null} while no runtime exists
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record ServiceAccount(
        UUID id,
        UUID organizationId,
        String code,
        String displayName,
        String purpose,
        String ownerLabel,
        Instant expiresAt,
        ServiceAccountStatus status,
        String disabledReason,
        Instant lastUsedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

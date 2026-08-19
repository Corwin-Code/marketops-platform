package com.mimococo.marketops.organizationaccount.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Legal entity owned by an organization.
 *
 * <p>Registration attributes beyond the registered name and country stay out of
 * the model until the owner supplies real registry inputs; absent values mean
 * not recorded, never a guessed default.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param code business code, unique inside the organization
 * @param displayName operator-facing name
 * @param registeredName registered legal name, or {@code null}
 * @param countryCode ISO 3166-1 alpha-2 code, or {@code null}
 * @param status lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record LegalEntity(
        UUID id,
        UUID organizationId,
        String code,
        String displayName,
        String registeredName,
        String countryCode,
        EntityStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

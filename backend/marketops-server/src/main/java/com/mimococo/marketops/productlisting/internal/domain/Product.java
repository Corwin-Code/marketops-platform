package com.mimococo.marketops.productlisting.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An internal product, above the sellable variant level.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param code business code, unique inside the organization
 * @param displayName operator-facing name
 * @param brandLabel recorded brand, or {@code null}
 * @param categoryLabel recorded category, or {@code null}
 * @param status lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record Product(
        UUID id,
        UUID organizationId,
        String code,
        String displayName,
        String brandLabel,
        String categoryLabel,
        EntityLifecycle status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

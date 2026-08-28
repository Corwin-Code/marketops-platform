package com.mimococo.marketops.productlisting.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The sellable internal unit that cost, stock and profit attach to.
 *
 * <p>Colour and size are recorded labels rather than coded dimensions. The
 * pilot cohort's vocabulary is the company's own, and forcing it into a fixed
 * taxonomy would lose the distinction an operator actually reads.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param productId owning product
 * @param skuCode internal SKU code, unique inside the organization
 * @param displayName operator-facing name
 * @param colorLabel recorded colour, or {@code null}
 * @param sizeLabel recorded size, or {@code null}
 * @param status lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record ProductVariant(
        UUID id,
        UUID organizationId,
        UUID productId,
        String skuCode,
        String displayName,
        String colorLabel,
        String sizeLabel,
        EntityLifecycle status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

package com.mimococo.marketops.organizationaccount.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Effective-dated declaration that a store operates under a fulfillment mode.
 *
 * <p>The declaration is independent of warehouse associations: a
 * marketplace-fulfilled store legitimately has no local warehouse link at all.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param storeId declaring store
 * @param fulfillmentModeCode generic fulfillment mode
 * @param effectiveFrom inclusive validity start
 * @param effectiveTo exclusive validity end, or {@code null}
 * @param status declaration lifecycle
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record StoreFulfillmentDeclaration(
        UUID id,
        UUID organizationId,
        UUID storeId,
        String fulfillmentModeCode,
        Instant effectiveFrom,
        Instant effectiveTo,
        AssociationStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

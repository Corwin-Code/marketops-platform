package com.mimococo.marketops.organizationaccount.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Effective-dated service association between one store and one warehouse for
 * one fulfillment mode.
 *
 * <p>Validity is the half-open interval [effectiveFrom, effectiveTo); a
 * {@code null} end means open-ended. Active intervals of the same store,
 * warehouse and mode never overlap — the relational exclusion constraint
 * enforces what the service pre-checks.
 *
 * @param id identifier
 * @param organizationId owning organization of both endpoints
 * @param storeId associated store
 * @param warehouseId associated warehouse
 * @param fulfillmentModeCode generic fulfillment mode
 * @param effectiveFrom inclusive validity start
 * @param effectiveTo exclusive validity end, or {@code null}
 * @param status association lifecycle
 * @param note operator note, or {@code null}
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record StoreWarehouseLink(
        UUID id,
        UUID organizationId,
        UUID storeId,
        UUID warehouseId,
        String fulfillmentModeCode,
        Instant effectiveFrom,
        Instant effectiveTo,
        AssociationStatus status,
        String note,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

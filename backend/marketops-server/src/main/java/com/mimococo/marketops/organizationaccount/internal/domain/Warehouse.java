package com.mimococo.marketops.organizationaccount.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Operational warehouse owned by a legal entity.
 *
 * <p>A warehouse is never a child of a store. Its service relationships to
 * stores are separate effective-dated association rows, so one warehouse can
 * serve many stores and a store can be served by many warehouses.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param legalEntityId owning legal entity
 * @param code business code, unique inside the organization
 * @param displayName operator-facing name
 * @param timezone IANA zone, or {@code null}
 * @param status lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record Warehouse(
        UUID id,
        UUID organizationId,
        UUID legalEntityId,
        String code,
        String displayName,
        String timezone,
        EntityStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

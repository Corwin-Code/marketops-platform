package com.mimococo.marketops.adminobservability.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter and keyset cursor for audit retrieval.
 *
 * <p>Results are ordered newest first. Paging continues strictly before the
 * position named by {@code beforeOccurredAt} and {@code beforeId}, which the
 * caller copies from the last entry of the previous page; both are set or both
 * are absent.
 *
 * @param actorId exact actor filter, or {@code null}
 * @param sourceDomain module filter, or {@code null}
 * @param action action filter, or {@code null}
 * @param entityType entity type filter, or {@code null}
 * @param entityId entity identifier filter, or {@code null}
 * @param occurredFrom inclusive lower time bound, or {@code null}
 * @param occurredTo exclusive upper time bound, or {@code null}
 * @param beforeOccurredAt keyset position time, or {@code null}
 * @param beforeId keyset position identifier, or {@code null}
 * @param limit maximum entries, 1..200
 */
public record AuditEventFilter(
        String actorId,
        AuditSourceDomain sourceDomain,
        AuditAction action,
        String entityType,
        UUID entityId,
        Instant occurredFrom,
        Instant occurredTo,
        Instant beforeOccurredAt,
        UUID beforeId,
        int limit) {
}

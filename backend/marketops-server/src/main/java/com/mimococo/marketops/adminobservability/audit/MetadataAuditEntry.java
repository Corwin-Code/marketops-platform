package com.mimococo.marketops.adminobservability.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One journaled audit event as returned by queries.
 *
 * @param id event identifier
 * @param occurredAt database server time of the event
 * @param actorType who the event is attributed to
 * @param actorId operator attribution or observing component
 * @param sourceDomain module the event belongs to
 * @param action what happened
 * @param entityType target entity type, or {@code null} for some denials
 * @param entityId target entity identifier, or {@code null}
 * @param entityCode target entity business code, or {@code null}
 * @param changeSummary field-level change document as JSON text, or {@code null}
 * @param denialCode stable error code for denials, or {@code null}
 * @param reason recorded reason, or {@code null}
 * @param correlationId request correlation identifier
 * @param evidenceRef evidence reference, or {@code null}
 */
public record MetadataAuditEntry(
        UUID id,
        Instant occurredAt,
        AuditActorType actorType,
        String actorId,
        AuditSourceDomain sourceDomain,
        AuditAction action,
        String entityType,
        UUID entityId,
        String entityCode,
        String changeSummary,
        String denialCode,
        String reason,
        String correlationId,
        String evidenceRef) {
}

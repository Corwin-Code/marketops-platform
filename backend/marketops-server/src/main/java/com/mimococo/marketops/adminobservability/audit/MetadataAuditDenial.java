package com.mimococo.marketops.adminobservability.audit;

import java.util.UUID;

/**
 * A refused mutation attempt to be journaled.
 *
 * <p>A denial may lack an entity — the target can be nonexistent — and may lack
 * a human actor: when attribution itself was missing or invalid, the event is
 * recorded under {@link AuditActorType#SYSTEM} with the observing component's
 * identity, and the rejected raw attribution value is never stored.
 *
 * @param sourceDomain module the refusal belongs to
 * @param actorType who the event is attributed to
 * @param actorId validated operator, or the fixed observing component
 * @param denialCode stable error code of the refusal
 * @param entityType target entity type, or {@code null}
 * @param entityId target entity identifier, or {@code null}
 * @param entityCode target entity business code, or {@code null}
 * @param reason safe context, or {@code null}
 */
public record MetadataAuditDenial(
        AuditSourceDomain sourceDomain,
        AuditActorType actorType,
        String actorId,
        String denialCode,
        String entityType,
        UUID entityId,
        String entityCode,
        String reason) {
}

package com.mimococo.marketops.adminobservability.audit;

import java.util.Map;
import java.util.UUID;

/**
 * A successful metadata mutation to be journaled.
 *
 * <p>Changes are always operator-attributed: the maintenance boundary refuses a
 * mutation without validated attribution before any service runs, so by the
 * time a change record exists the operator identity is known.
 *
 * @param sourceDomain module the mutation belongs to
 * @param actorId validated operator attribution
 * @param action what happened
 * @param entityType stable entity type name
 * @param entityId entity identifier
 * @param entityCode entity business code, or {@code null}
 * @param changes field-level before/after values, or an empty map
 * @param reason operator-supplied reason, or {@code null}
 * @param evidenceRef evidence reference for verification events, or {@code null}
 */
public record MetadataAuditChange(
        AuditSourceDomain sourceDomain,
        String actorId,
        AuditAction action,
        String entityType,
        UUID entityId,
        String entityCode,
        Map<String, FieldChange> changes,
        String reason,
        String evidenceRef) {
}

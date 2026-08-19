package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One appended verification or availability transition with its evidence.
 *
 * <p>Exactly one target reference is set. The journal is append-only: rows are
 * inserted and read, never updated, and {@code occurredAt} is stamped by the
 * database clock.
 *
 * @param id identifier
 * @param capabilityId capability target, or {@code null}
 * @param endpointId endpoint target, or {@code null}
 * @param capabilitySubjectStatusId subject-status target, or {@code null}
 * @param platformPermissionRequirementId requirement target, or {@code null}
 * @param fromState state before the transition
 * @param toState state after the transition
 * @param evidenceRef reference to the supporting evidence, or {@code null}
 * @param sourceTitle title of the evidence source, or {@code null}
 * @param verifiedAt verification time carried by the evidence, or {@code null}
 * @param actor operator who recorded the transition
 * @param reason free-text reason, or {@code null}
 * @param occurredAt database-clock insertion time
 * @param correlationId request correlation identifier
 */
public record VerificationEvent(
        UUID id,
        UUID capabilityId,
        UUID endpointId,
        UUID capabilitySubjectStatusId,
        UUID platformPermissionRequirementId,
        String fromState,
        String toState,
        String evidenceRef,
        String sourceTitle,
        Instant verifiedAt,
        String actor,
        String reason,
        Instant occurredAt,
        String correlationId) {
}

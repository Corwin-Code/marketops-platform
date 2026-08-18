package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One permission the platform itself requires for a capability or endpoint.
 *
 * <p>The external code is the platform's identifier stored opaquely; it is
 * disjoint from the internal permission taxonomy. Exactly one of the
 * capability and endpoint references is set.
 *
 * @param id identifier
 * @param platformCode platform whose requirement this is
 * @param capabilityId capability target, or {@code null}
 * @param endpointId endpoint target, or {@code null}
 * @param requirementKind kind of requirement in the platform's language
 * @param externalCode the platform's own identifier for the requirement
 * @param description free-text description, or {@code null}
 * @param verificationState recorded verification state
 * @param lastVerifiedAt time of the recorded verification, or {@code null}
 * @param evidenceRef reference to the verification evidence, or {@code null}
 * @param verifiedSourceTitle title of the verified source, or {@code null}
 * @param status registry lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record PermissionRequirement(
        UUID id,
        String platformCode,
        UUID capabilityId,
        UUID endpointId,
        RequirementKind requirementKind,
        String externalCode,
        String description,
        VerificationState verificationState,
        Instant lastVerifiedAt,
        String evidenceRef,
        String verifiedSourceTitle,
        RegistryStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

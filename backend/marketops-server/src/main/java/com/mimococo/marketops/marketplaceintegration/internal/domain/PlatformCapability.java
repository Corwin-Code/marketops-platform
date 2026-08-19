package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One platform-neutral logical capability in the registry.
 *
 * <p>The capability code is an internal registry name, never a platform API
 * name. Verification is fail-closed: {@code UNKNOWN} and {@code UNVERIFIED}
 * rows never enable behaviour, and {@code VERIFIED} requires complete
 * provenance at the schema level.
 *
 * @param id identifier
 * @param platformCode marketplace platform the capability belongs to
 * @param capabilityCode internal registry code, unique inside the platform
 * @param displayName operator-facing name
 * @param description free-text description, or {@code null}
 * @param appliesTo subject level the capability is evaluated against
 * @param readWriteClass whether the capability reads or mutates platform state
 * @param subscriptionRequired whether the platform requires a subscription
 * @param verificationState recorded verification state
 * @param lastVerifiedAt time of the recorded verification, or {@code null}
 * @param evidenceRef reference to the verification evidence, or {@code null}
 * @param verifiedSourceTitle title of the verified source, or {@code null}
 * @param ownerLabel person or team responsible for keeping the row current
 * @param contractTestStatus recorded contract-test standing
 * @param deprecatedAt deprecation time, or {@code null}
 * @param replacementCapabilityId same-platform successor, or {@code null}
 * @param status registry lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record PlatformCapability(
        UUID id,
        String platformCode,
        String capabilityCode,
        String displayName,
        String description,
        CapabilityAppliesTo appliesTo,
        ReadWriteClass readWriteClass,
        TriState subscriptionRequired,
        VerificationState verificationState,
        Instant lastVerifiedAt,
        String evidenceRef,
        String verifiedSourceTitle,
        String ownerLabel,
        ContractTestStatus contractTestStatus,
        Instant deprecatedAt,
        UUID replacementCapabilityId,
        RegistryStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

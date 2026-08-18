package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Recorded availability of one capability for one concrete subject.
 *
 * <p>Exactly one of the account and store references is set. Any availability
 * other than {@code UNKNOWN} carries complete provenance, enforced by the
 * schema.
 *
 * @param id identifier
 * @param organizationId organization owning the subject
 * @param platformCode platform shared by the capability and the subject
 * @param capabilityId capability the record is about
 * @param marketplaceAccountId account subject, or {@code null}
 * @param storeId store subject, or {@code null}
 * @param availability recorded availability
 * @param lastVerifiedAt time of the recorded verification, or {@code null}
 * @param evidenceRef reference to the verification evidence, or {@code null}
 * @param verifiedSourceTitle title of the verified source, or {@code null}
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record CapabilitySubjectStatus(
        UUID id,
        UUID organizationId,
        String platformCode,
        UUID capabilityId,
        UUID marketplaceAccountId,
        UUID storeId,
        Availability availability,
        Instant lastVerifiedAt,
        String evidenceRef,
        String verifiedSourceTitle,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

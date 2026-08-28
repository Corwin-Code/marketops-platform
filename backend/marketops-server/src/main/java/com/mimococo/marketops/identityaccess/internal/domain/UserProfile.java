package com.mimococo.marketops.identityaccess.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The MarketOps profile of one human, bound to one external subject.
 *
 * <p>{@code credentialsValidFrom} is what makes revocation immediate. A token
 * issued before that instant is refused, so disabling somebody stops their
 * existing sessions now instead of when the provider's own expiry eventually
 * catches up.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param identityProviderId issuer this subject belongs to
 * @param externalSubject the provider's opaque identifier for the person
 * @param loginHint the provider's preferred user name, or {@code null}
 * @param displayName operator-facing name
 * @param contactEmail business contact address, or {@code null}
 * @param status lifecycle status
 * @param disabledReason why the profile is not active, or {@code null}
 * @param credentialsValidFrom tokens issued before this instant are refused
 * @param lastSeenAt last accepted request, or {@code null}
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record UserProfile(
        UUID id,
        UUID organizationId,
        UUID identityProviderId,
        String externalSubject,
        String loginHint,
        String displayName,
        String contactEmail,
        UserAccountStatus status,
        String disabledReason,
        Instant credentialsValidFrom,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** Whether this profile may act right now. */
    public boolean isActive() {
        return status == UserAccountStatus.ACTIVE;
    }
}

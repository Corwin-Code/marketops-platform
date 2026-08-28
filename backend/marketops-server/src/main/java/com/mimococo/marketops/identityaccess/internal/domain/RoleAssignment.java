package com.mimococo.marketops.identityaccess.internal.domain;

import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import java.time.Instant;
import java.util.UUID;

/**
 * One role held by one person over one half-open interval.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param userId profile holding the role
 * @param role the business role
 * @param effectiveFrom start of the interval
 * @param effectiveTo end of the interval, or {@code null} while open
 * @param status whether the assignment stands
 * @param reason why it was revoked, or {@code null}
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record RoleAssignment(
        UUID id,
        UUID organizationId,
        UUID userId,
        BusinessRoleCode role,
        Instant effectiveFrom,
        Instant effectiveTo,
        GrantStatus status,
        String reason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

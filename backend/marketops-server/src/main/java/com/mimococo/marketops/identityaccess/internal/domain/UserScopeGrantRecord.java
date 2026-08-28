package com.mimococo.marketops.identityaccess.internal.domain;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import java.time.Instant;
import java.util.UUID;

/**
 * One action allowed on one resource for one person over one interval.
 *
 * <p>The resource is stored as a type and an identifier here; the relational
 * layer keeps five typed columns so a grant cannot reference a resource outside
 * the grant's own organization.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param userId profile holding the grant
 * @param action the action granted
 * @param resourceType kind of resource the grant names
 * @param resourceId identifier of that resource
 * @param effectiveFrom start of the interval
 * @param effectiveTo end of the interval, or {@code null} while open
 * @param status whether the grant stands
 * @param reason why it was revoked, or {@code null}
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record UserScopeGrantRecord(
        UUID id,
        UUID organizationId,
        UUID userId,
        ActionScopeCode action,
        ResourceScopeType resourceType,
        UUID resourceId,
        Instant effectiveFrom,
        Instant effectiveTo,
        GrantStatus status,
        String reason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

package com.mimococo.marketops.identityaccess.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit positive grant of one permission kind on exactly one resource.
 *
 * <p>Denial is the default: nothing is granted implicitly, no permission kind
 * implies another, and a grant never derives further grants. The resource is
 * always inside the service account's organization — relationally enforced —
 * and validity is the half-open interval [effectiveFrom, effectiveTo)
 * intersected with the active status.
 *
 * @param id identifier
 * @param organizationId organization of subject and resource
 * @param serviceAccountId granted subject
 * @param permissionCode granted permission kind
 * @param resourceType kind of the granted resource
 * @param resourceId identifier of the granted resource
 * @param effectiveFrom inclusive validity start
 * @param effectiveTo exclusive validity end, or {@code null}
 * @param status {@code ACTIVE} or {@code REVOKED}
 * @param reason grant or revocation reason, or {@code null}
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record ScopeGrant(
        UUID id,
        UUID organizationId,
        UUID serviceAccountId,
        String permissionCode,
        ScopeResourceType resourceType,
        UUID resourceId,
        Instant effectiveFrom,
        Instant effectiveTo,
        ScopeGrantStatus status,
        String reason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

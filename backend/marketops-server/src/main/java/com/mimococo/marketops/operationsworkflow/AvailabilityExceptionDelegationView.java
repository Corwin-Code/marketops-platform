package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import java.time.Instant;
import java.util.UUID;

/** One immutable, effective-dated accepted-risk decision delegation. */
public record AvailabilityExceptionDelegationView(
        UUID id,
        UUID organizationId,
        String delegationReference,
        UUID delegateUserId,
        BusinessRoleCode delegatedRole,
        UUID grantedByUserId,
        BusinessRoleCode grantedByRole,
        Instant effectiveFrom,
        Instant effectiveTo,
        String evidenceReference,
        Instant grantedAt,
        Instant revokedAt,
        UUID revokedByUserId,
        String revocationReason) {
}

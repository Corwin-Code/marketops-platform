package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Deliberately constructs a grant beside, but outside, the trusted row mapper. */
public final class UntrustedGrantConstructorFixture {

    CallAuthorityGrant manufacture(UUID endpointId, UUID credentialId) {
        Instant grantedAt = Instant.now();
        return new CallAuthorityGrant(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                "untrusted-worker", "OZON", endpointId, credentialId, UUID.randomUUID(), 1,
                grantedAt, grantedAt.plusSeconds(30), grantedAt.plusSeconds(60),
                grantedAt.plusSeconds(30),
                List.of("JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT"),
                List.of(1L, 1L, 1L, 1L), "b".repeat(64));
    }
}

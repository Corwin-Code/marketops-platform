package com.mimococo.marketops.testfixture.violation.insidegrant.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CallAuthorityGrant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Deliberately manufactures a grant outside the trusted JDBC mapper. */
public final class UntrustedGrantRebinder {

    /** Rebind arbitrary identities despite residing in the owning module. */
    public CallAuthorityGrant rebind(UUID endpointId, UUID credentialId) {
        Instant grantedAt = Instant.now();
        return new CallAuthorityGrant(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                "inside-worker", "OZON", endpointId, credentialId, UUID.randomUUID(), 1,
                grantedAt, grantedAt.plusSeconds(30), grantedAt.plusSeconds(60),
                grantedAt.plusSeconds(30),
                List.of("JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT"),
                List.of(1L, 1L, 1L, 1L), "b".repeat(64));
    }
}

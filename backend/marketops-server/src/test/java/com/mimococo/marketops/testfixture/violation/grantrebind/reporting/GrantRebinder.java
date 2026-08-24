package com.mimococo.marketops.testfixture.violation.grantrebind.reporting;

import com.mimococo.marketops.marketplaceintegration.port.CallAuthorityGrant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Deliberately manufactures a grant outside the owning module. */
public final class GrantRebinder {

    /** Build the invalid arrangement that the architecture rule must reject. */
    public CallAuthorityGrant rebind(UUID endpointId, UUID credentialId) {
        Instant grantedAt = Instant.now();
        return new CallAuthorityGrant(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                "outside-worker", "OZON", endpointId, credentialId, UUID.randomUUID(), 1,
                grantedAt, grantedAt.plusSeconds(30),
                List.of("JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT"),
                List.of(1L, 1L, 1L, 1L), "a".repeat(64));
    }
}

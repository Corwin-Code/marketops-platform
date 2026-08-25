package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Deliberately invokes the request factory outside the internal executor. */
public final class AcquisitionRequestRebinderFixture {

    /** Manufacture the invalid call site that the exact allowlist must reject. */
    public AcquisitionRequest rebind() {
        Instant grantedAt = Instant.now();
        return AcquisitionRequest.fromDatabaseAuthority(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                "fixture-worker", "OZON", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, grantedAt, grantedAt.plusSeconds(30),
                grantedAt.plusSeconds(60), grantedAt.plusSeconds(30),
                List.of("JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT"),
                List.of(1L, 1L, 1L, 1L), "f".repeat(64));
    }
}

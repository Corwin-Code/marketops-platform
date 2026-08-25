package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import java.time.Clock;
import java.util.Objects;

/**
 * Internal doorway that atomically consumes one database grant before invoking acquisition.
 *
 * <p>Only {@link JdbcAuthorizedAcquisitionGateway} may call this class. The CAS on the grant
 * rejects a second sequential or concurrent use before the acquisition port is reached.
 */
final class AuthorizedAcquisitionExecutor {

    private final AcquisitionPort acquisition;
    private final Clock clock;

    AuthorizedAcquisitionExecutor(AcquisitionPort acquisition) {
        this(acquisition, Clock.systemUTC());
    }

    AuthorizedAcquisitionExecutor(AcquisitionPort acquisition, Clock clock) {
        this.acquisition = Objects.requireNonNull(acquisition, "acquisition");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Consume and execute the exact database decision once. */
    AcquisitionResult execute(CallAuthorityGrant grant) {
        Objects.requireNonNull(grant, "grant");
        if (!grant.consumeForAcquisition()) {
            throw new IllegalStateException("the call authority was already consumed");
        }
        if (!grant.callAuthorityExpiresAt().isAfter(clock.instant())) {
            throw new IllegalStateException("the call authority expired before acquisition");
        }
        return acquisition.acquire(AcquisitionRequest.fromDatabaseAuthority(
                grant.decisionId(),
                grant.jobId(),
                grant.runId(),
                grant.fenceToken(),
                grant.leaseOwner(),
                grant.platformCode(),
                grant.endpointId(),
                grant.credentialId(),
                grant.scopeGrantId(),
                grant.callSeq(),
                grant.grantedAt(),
                grant.callAuthorityExpiresAt(),
                grant.runLeaseExpiresAt(),
                grant.serverPolicyDeadline(),
                grant.controlEpochScopes(),
                grant.controlEpochValues(),
                grant.boundarySetDigest()));
    }
}

package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CredentialLookupRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves which credential a write is authenticated with.
 *
 * <p>Separate from the worker so the resolution is made once, at the moment of
 * the call, against the clock rather than against whatever was true when the
 * command was created. A credential that expired or was revoked between
 * approval and execution must not be used, and the only way to guarantee that
 * is to ask at the last possible moment.
 */
@Service
public class CredentialDirectory {

    private final CredentialLookupRepository credentials;
    private final Clock clock;

    CredentialDirectory(CredentialLookupRepository credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
    }

    /** The credential a write capability may be exercised with for one store. */
    @Transactional(readOnly = true)
    public Optional<UUID> writeCredential(UUID storeId, UUID capabilityId) {
        return credentials.writeCredential(storeId, capabilityId, clock.instant());
    }
}

package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Clock;
import java.util.Objects;

/**
 * The sole factory and executor for an authority-bound acquisition request.
 *
 * <p>Production callers supply the structured result returned by the database
 * grant primitive. This executor derives the request without accepting any
 * replacement identity and immediately passes it to the single acquisition
 * doorway. Architecture rules allow only this class to create the request and
 * invoke acquisition, while the trusted JDBC result mapper alone constructs the
 * grant.
 */
public final class AuthorizedAcquisitionExecutor {

    private final AcquisitionPort acquisition;
    private final Clock clock;

    public AuthorizedAcquisitionExecutor(AcquisitionPort acquisition) {
        this(acquisition, Clock.systemUTC());
    }

    AuthorizedAcquisitionExecutor(AcquisitionPort acquisition, Clock clock) {
        this.acquisition = Objects.requireNonNull(acquisition, "acquisition");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Execute the exact call described by {@code grant}. */
    public AcquisitionResult execute(CallAuthorityGrant grant) {
        Objects.requireNonNull(grant, "grant");
        if (!grant.callAuthorityExpiresAt().isAfter(clock.instant())) {
            throw new IllegalStateException(
                    "the call authority expired at " + grant.callAuthorityExpiresAt());
        }
        return acquisition.acquire(AcquisitionRequest.from(grant));
    }
}

package com.mimococo.marketops.identityaccess.internal.domain;

import java.util.Set;

/**
 * Recorded lifecycle of a service account.
 *
 * <p>{@code DISABLED} is recoverable, {@code REVOKED} is terminal. Expiry is
 * not a status: it is derived at evaluation time so the operator's recorded
 * intent survives the clock.
 */
public enum ServiceAccountStatus {

    ACTIVE,
    DISABLED,
    REVOKED;

    /** The statuses this status may transition to. */
    public Set<ServiceAccountStatus> allowedTransitions() {
        return switch (this) {
            case ACTIVE -> Set.of(DISABLED, REVOKED);
            case DISABLED -> Set.of(ACTIVE, REVOKED);
            case REVOKED -> Set.of();
        };
    }

    /** Whether this status may transition to {@code target}. */
    public boolean canTransitionTo(ServiceAccountStatus target) {
        return allowedTransitions().contains(target);
    }
}

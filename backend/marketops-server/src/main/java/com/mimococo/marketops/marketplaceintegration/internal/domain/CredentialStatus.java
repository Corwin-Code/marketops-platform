package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.util.Set;

/**
 * Recorded lifecycle of credential metadata.
 *
 * <p>{@code DISABLED} is recoverable, {@code REVOKED} is terminal and releases
 * the credential's secret reference for a future credential. Expiry is derived
 * from the mandatory expiry timestamp, never stored back.
 */
public enum CredentialStatus {

    ACTIVE,
    DISABLED,
    REVOKED;

    /** The statuses this status may transition to. */
    public Set<CredentialStatus> allowedTransitions() {
        return switch (this) {
            case ACTIVE -> Set.of(DISABLED, REVOKED);
            case DISABLED -> Set.of(ACTIVE, REVOKED);
            case REVOKED -> Set.of();
        };
    }

    /** Whether this status may transition to {@code target}. */
    public boolean canTransitionTo(CredentialStatus target) {
        return allowedTransitions().contains(target);
    }
}

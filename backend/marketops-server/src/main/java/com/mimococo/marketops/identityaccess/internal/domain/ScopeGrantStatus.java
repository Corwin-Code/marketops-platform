package com.mimococo.marketops.identityaccess.internal.domain;

import java.util.Set;

/** Lifecycle of a scoped permission grant: revocation is terminal. */
public enum ScopeGrantStatus {

    ACTIVE,
    REVOKED;

    /** The statuses this status may transition to. */
    public Set<ScopeGrantStatus> allowedTransitions() {
        return switch (this) {
            case ACTIVE -> Set.of(REVOKED);
            case REVOKED -> Set.of();
        };
    }

    /** Whether this status may transition to {@code target}. */
    public boolean canTransitionTo(ScopeGrantStatus target) {
        return allowedTransitions().contains(target);
    }
}

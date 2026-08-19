package com.mimococo.marketops.identityaccess.internal.domain;

import java.util.Set;

/** Lifecycle of an allowed-source declaration: withdrawal is terminal. */
public enum AllowedSourceStatus {

    ACTIVE,
    WITHDRAWN;

    /** The statuses this status may transition to. */
    public Set<AllowedSourceStatus> allowedTransitions() {
        return switch (this) {
            case ACTIVE -> Set.of(WITHDRAWN);
            case WITHDRAWN -> Set.of();
        };
    }

    /** Whether this status may transition to {@code target}. */
    public boolean canTransitionTo(AllowedSourceStatus target) {
        return allowedTransitions().contains(target);
    }
}

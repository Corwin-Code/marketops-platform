package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.util.Set;

/** Lifecycle of a credential store-scope row: withdrawal is terminal. */
public enum StoreScopeStatus {

    ACTIVE,
    WITHDRAWN;

    /** The statuses this status may transition to. */
    public Set<StoreScopeStatus> allowedTransitions() {
        return switch (this) {
            case ACTIVE -> Set.of(WITHDRAWN);
            case WITHDRAWN -> Set.of();
        };
    }

    /** Whether this status may transition to {@code target}. */
    public boolean canTransitionTo(StoreScopeStatus target) {
        return allowedTransitions().contains(target);
    }
}

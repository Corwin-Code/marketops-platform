package com.mimococo.marketops.organizationaccount.internal.domain;

import java.util.Set;

/**
 * Lifecycle of an operating entity.
 *
 * <p>{@code ACTIVE} and {@code SUSPENDED} alternate freely; {@code RETIRED} is
 * terminal. A suspended entity stays readable and queryable but cannot become
 * the target of a new child, association, grant or credential. Recovery from a
 * mistaken retirement is a new entity with a new code — history is never
 * rewritten.
 */
public enum EntityStatus {

    ACTIVE,
    SUSPENDED,
    RETIRED;

    /** The statuses this status may transition to. */
    public Set<EntityStatus> allowedTransitions() {
        return switch (this) {
            case ACTIVE -> Set.of(SUSPENDED, RETIRED);
            case SUSPENDED -> Set.of(ACTIVE, RETIRED);
            case RETIRED -> Set.of();
        };
    }

    /** Whether this status may transition to {@code target}. */
    public boolean canTransitionTo(EntityStatus target) {
        return allowedTransitions().contains(target);
    }
}

package com.mimococo.marketops.organizationaccount.internal.domain;

import java.util.Set;

/**
 * Lifecycle of an effective-dated association row.
 *
 * <p>{@code ENDED} records a normal conclusion and {@code CANCELLED} records a
 * row created in error; both are terminal and both retain the row, because an
 * association that existed — even mistakenly — is history.
 */
public enum AssociationStatus {

    ACTIVE,
    ENDED,
    CANCELLED;

    /** The statuses this status may transition to. */
    public Set<AssociationStatus> allowedTransitions() {
        return switch (this) {
            case ACTIVE -> Set.of(ENDED, CANCELLED);
            case ENDED, CANCELLED -> Set.of();
        };
    }

    /** Whether this status may transition to {@code target}. */
    public boolean canTransitionTo(AssociationStatus target) {
        return allowedTransitions().contains(target);
    }
}

package com.mimococo.marketops.identityaccess.internal.domain;

/**
 * Lifecycle of a MarketOps profile.
 *
 * <p>Only {@link #ACTIVE} may act. Suspension and disabling are recorded states
 * rather than deletions, so a person's history, grants and audit attribution
 * survive their departure and can be read afterwards.
 */
public enum UserAccountStatus {
    ACTIVE,
    SUSPENDED,
    DISABLED
}

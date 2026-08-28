package com.mimococo.marketops.identityaccess.internal.domain;

/**
 * Whether a role assignment or scope grant currently stands.
 *
 * <p>A withdrawn grant becomes {@link #REVOKED} with a reason rather than
 * disappearing, so "who could do this last month" stays answerable.
 */
public enum GrantStatus {
    ACTIVE,
    REVOKED
}

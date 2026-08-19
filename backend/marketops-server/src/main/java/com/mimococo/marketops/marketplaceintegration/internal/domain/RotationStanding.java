package com.mimococo.marketops.marketplaceintegration.internal.domain;

/**
 * Derived rotation position of a credential.
 *
 * <p>{@code BEING_REPLACED} means a non-revoked successor names this credential
 * through its replacement lineage. There is no stored rotation state; the
 * lineage and the status machine are the single source of truth.
 */
public enum RotationStanding {
    STABLE,
    BEING_REPLACED
}

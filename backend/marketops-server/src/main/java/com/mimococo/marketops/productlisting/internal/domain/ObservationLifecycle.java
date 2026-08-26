package com.mimococo.marketops.productlisting.internal.domain;

/**
 * Whether a platform listing is still being observed.
 *
 * <p>A listing that disappears from a source becomes {@link #ARCHIVED} rather
 * than being deleted. Its identity, its history and everything that referenced
 * it stay readable, which is what a diagnosis of a SKU that stopped selling
 * actually needs.
 */
public enum ObservationLifecycle {
    OBSERVED,
    ARCHIVED
}

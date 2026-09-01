package com.mimococo.marketops.availabilityrisk.internal.domain;

/**
 * How a platform-visible quantity relates to stock the company already counted.
 *
 * <p>{@code UNDECLARED} is not a shrug. It is the state that stops a company
 * answer being called safe, because units nobody has classified might be the
 * same goods counted twice or might be genuinely extra, and the difference
 * decides whether a variant is covered.
 */
public enum SupplyDistinctness {

    /** The platform view shows the same physical units an internal warehouse holds. */
    MIRRORS_INTERNAL,

    /** The company owns these units and they are held separately at the platform. */
    PHYSICALLY_DISTINCT,

    /** Nobody has declared which of the two this is. */
    UNDECLARED
}

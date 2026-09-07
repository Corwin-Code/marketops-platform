package com.mimococo.marketops.analyticsdecision;

/**
 * What a metric or a finding is about.
 *
 * <p>A platform listing variant is the usual subject, because that is what a
 * price, a stock figure and a marketplace fee attach to. An internal variant is
 * the subject when a fact is the company's own, and a store is the subject of an
 * aggregate.
 *
 * <p>An advertising object is its own subject rather than the listing it
 * promotes. One object can carry traffic for several listings and one listing
 * can be promoted by several objects, so attributing a bid to a listing variant
 * would either double-count or silently pick one.
 */
public enum SubjectKind {
    PRODUCT_VARIANT,
    PLATFORM_LISTING_VARIANT,
    STORE,
    AD_NATIVE_OBJECT
}

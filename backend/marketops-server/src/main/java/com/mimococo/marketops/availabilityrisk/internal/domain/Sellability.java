package com.mimococo.marketops.availabilityrisk.internal.domain;

/**
 * Whether a marketplace listing could be bought.
 *
 * <p>{@code UNKNOWN} is first-class. A source that does not publish sellability
 * has not said the listing is fine, and coercing silence into {@code SELLABLE}
 * is how a blocked listing keeps a green card.
 */
public enum Sellability {
    /** The source stated the listing can be bought. */
    SELLABLE,
    /** The source stated the listing cannot be bought. */
    NOT_SELLABLE,
    /** No source stated either way. */
    UNKNOWN
}

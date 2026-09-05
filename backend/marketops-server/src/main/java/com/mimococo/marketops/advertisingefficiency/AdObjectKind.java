package com.mimococo.marketops.advertisingefficiency;

/**
 * The kind of native advertising object a case is about.
 *
 * <p>This is descriptive, not permissive. Knowing that an object is a
 * {@code KEYWORD} says nothing about whether the marketplace lets us bid on it
 * independently — that is recorded separately, on the object itself, and proved
 * from official evidence. The two were deliberately kept apart because the
 * tempting shortcut is to assume that a fine-grained kind implies fine-grained
 * control, and on both target marketplaces that assumption is sometimes false.
 */
public enum AdObjectKind {

    /** A campaign, the coarsest object either marketplace exposes. */
    CAMPAIGN,

    /** A group of ads inside a campaign. */
    AD_GROUP,

    /** A targeting entry: a product, a category or an audience. */
    TARGET,

    /** A search keyword. */
    KEYWORD,

    /** A placement or surface, where the marketplace models one. */
    PLACEMENT,

    /** The platform's own object taxonomy has not been verified for this row. */
    UNKNOWN
}

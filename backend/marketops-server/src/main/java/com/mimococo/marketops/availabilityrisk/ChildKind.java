package com.mimococo.marketops.availabilityrisk;

/**
 * Which of a card's two independently governed risks a row is.
 *
 * <p>They are separate because they fail differently. A channel is one exact
 * observed listing and mode, so a fresh observation of it is actionable on its
 * own. A company answer covers everything the organization owns, so it cannot
 * be safe while any material input is missing.
 */
public enum ChildKind {

    /** Platform + store + listing variant + fulfillment mode. */
    CHANNEL,

    /** Organization + internal product variant. */
    COMPANY
}

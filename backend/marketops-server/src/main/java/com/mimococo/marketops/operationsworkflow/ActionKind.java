package com.mimococo.marketops.operationsworkflow;

/**
 * What a recommendation proposes to do about a subject.
 *
 * <p>Exactly three of these have a platform write behind them. Everything else is
 * work a person performs, which is why the distinction is carried in the type
 * rather than discovered later: a recommendation whose action has no write
 * capability never enters the command path at all, and cannot be approved into
 * one by mistake.
 *
 * <p>{@code ADVERTISING_REVIEW} and {@code AD_BID_CHANGE} are both about
 * advertising and only one of them can reach a marketplace. The review is the
 * route for everything this product deliberately does not write — a budget, a
 * campaign status, a targeting structure — and it carries no capability, so
 * approving one can never produce a command.
 */
public enum ActionKind {

    /** Change the price a marketplace holds for a listing variant. */
    PRICE_CHANGE(true),

    /** Change the bid a marketplace holds for one advertising object. */
    AD_BID_CHANGE(true),

    /** Resolve a listing-to-SKU mapping a person must judge. */
    RESOLVE_MAPPING(false),

    /** Review replenishment for a variant running out or overstocked. */
    RESTOCK_REVIEW(false),

    /** Review listing content where the funnel points at presentation. */
    LISTING_CONTENT_REVIEW(false),

    /** Review advertising spend against what it returns. */
    ADVERTISING_REVIEW(false),

    /** Correct or supply the cost data a profit figure depends on. */
    COST_DATA_REVIEW(false),

    /**
     * Change the Russian description a marketplace holds for one listing.
     *
     * <p>The third and last controlled write. It reaches exactly one attribute
     * of one listing, on the API path only after launch, and never a whole card.
     */
    LISTING_DESCRIPTION_CHANGE(true),

    /**
     * Enter, adopt or exit a simple promotion for one listing.
     *
     * <p>Governed manual work with obligations and two separate releases. It
     * carries no write capability, so approving one can never produce a command.
     */
    LISTING_PROMOTION_ACTION(false);

    private final boolean writeCapable;

    ActionKind(boolean writeCapable) {
        this.writeCapable = writeCapable;
    }

    /** Whether this product has a platform write capability for the action. */
    public boolean writeCapable() {
        return writeCapable;
    }

    /**
     * Whether a person must approve the action before anything happens.
     *
     * <p>Every write-capable action, and the promotion action: it reaches a
     * marketplace through a governed manual packet rather than a command, and a
     * packet is issued only from a launched, approved action.
     */
    public boolean requiresApproval() {
        return writeCapable || this == LISTING_PROMOTION_ACTION;
    }

    /** Whether this is one of the two listing conversion actions. */
    public boolean listingAction() {
        return this == LISTING_DESCRIPTION_CHANGE || this == LISTING_PROMOTION_ACTION;
    }
}

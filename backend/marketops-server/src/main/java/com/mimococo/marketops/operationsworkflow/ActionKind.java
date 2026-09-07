package com.mimococo.marketops.operationsworkflow;

/**
 * What a recommendation proposes to do about a subject.
 *
 * <p>Exactly two of these have a platform write behind them. Everything else is
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
    COST_DATA_REVIEW(false);

    private final boolean writeCapable;

    ActionKind(boolean writeCapable) {
        this.writeCapable = writeCapable;
    }

    /** Whether this product has a platform write capability for the action. */
    public boolean writeCapable() {
        return writeCapable;
    }
}

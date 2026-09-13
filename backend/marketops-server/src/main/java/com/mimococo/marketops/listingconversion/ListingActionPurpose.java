package com.mimococo.marketops.listingconversion;

/** Existing accepted purposes; a purpose is not itself business-safety qualification. */
public enum ListingActionPurpose {
    LISTING_CONVERSION,
    DESCRIPTION_CORRECTION,
    BOUNDED_EXPLORATION,
    PROMOTION;

    public boolean requiresFormalEvaluation() {
        return this==LISTING_CONVERSION || this==PROMOTION;
    }
}

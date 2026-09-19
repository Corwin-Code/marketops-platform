package com.mimococo.marketops.listingconversion;

/** The reviewed states of a listing action; the database holds the edge set as data. */
public enum ListingActionState {
    DRAFT,
    REVIEWED,
    APPROVED,
    APPROVED_NOT_LAUNCHABLE,
    LAUNCHED,
    VERIFIED,
    CLOSED,
    CANCELLED,
    CONTAINED
}

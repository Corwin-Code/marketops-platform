package com.mimococo.marketops.marketplaceintegration.internal.domain;

/** Recorded pagination behaviour of a platform endpoint. */
public enum PaginationModel {
    CURSOR,
    OFFSET,
    PAGE,
    DATE_WINDOW,
    /** The next page asks after the key of the previous page's last record; an empty page ends. */
    LAST_RECORD_KEY,
    NONE,
    UNKNOWN
}

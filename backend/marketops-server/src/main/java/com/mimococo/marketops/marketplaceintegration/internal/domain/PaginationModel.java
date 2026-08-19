package com.mimococo.marketops.marketplaceintegration.internal.domain;

/** Recorded pagination behaviour of a platform endpoint. */
public enum PaginationModel {
    CURSOR,
    OFFSET,
    PAGE,
    DATE_WINDOW,
    NONE,
    UNKNOWN
}

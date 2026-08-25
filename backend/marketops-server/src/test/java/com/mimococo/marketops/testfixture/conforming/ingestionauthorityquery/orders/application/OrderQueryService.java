package com.mimococo.marketops.testfixture.conforming.ingestionauthorityquery.orders.application;

/** Ordinary read service with no acquisition or object-storage authority. */
public final class OrderQueryService {

    /** Return a bounded query result. */
    public String summary() {
        return "no acquisition authority";
    }
}

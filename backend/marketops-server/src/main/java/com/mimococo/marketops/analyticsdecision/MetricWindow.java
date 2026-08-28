package com.mimococo.marketops.analyticsdecision;

import java.time.Duration;

/**
 * The observation windows this product computes over.
 *
 * <p>Three windows exist because a return window changes what a sale means. A
 * seven-day answer is early and volatile, a thirty-day answer is late and
 * settled, and comparing the two is how an operator sees whether a change held.
 * Thirty days is the primary default the product contract fixes.
 */
public enum MetricWindow {

    /** Seven days. */
    D7(7),

    /** Fourteen days. */
    D14(14),

    /** Thirty days, the primary default. */
    D30(30);

    private final int days;

    MetricWindow(int days) {
        this.days = days;
    }

    /** How many days the window covers. */
    public int days() {
        return days;
    }

    /** The window's length. */
    public Duration length() {
        return Duration.ofDays(days);
    }
}

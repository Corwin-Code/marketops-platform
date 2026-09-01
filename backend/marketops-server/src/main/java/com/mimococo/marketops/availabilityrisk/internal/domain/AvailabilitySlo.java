package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import java.time.Duration;

/**
 * How quickly an accepted fact has to become an updated answer.
 *
 * <p>These bounds are the Slice's own declared obligation rather than an
 * organization's configuration, which is why they are constants here and not a
 * policy table. An organization cannot make the product slower by publishing a
 * looser number; it can only observe whether the product met what it promised.
 *
 * <p>Two bounds, not one. The distribution target is what the product is built
 * to achieve for the lane that matters most; the hard bound is what no single
 * recalculation may exceed. Reporting only the percentile would hide a single
 * recalculation that took an hour, and reporting only the worst case would call
 * a healthy system unhealthy after one slow afternoon.
 */
public final class AvailabilitySlo {

    /** The distribution target for a critical answer. */
    public static final Duration CRITICAL_DISTRIBUTION_TARGET = Duration.ofMinutes(5);

    /** The bound no single recalculation may exceed, whatever its lane. */
    public static final Duration HARD_BOUND = Duration.ofMinutes(15);

    /** The percentile the distribution target is judged at. */
    public static final int TARGET_PERCENTILE = 95;

    private AvailabilitySlo() {
    }

    /**
     * Whether one recalculation missed the obligation.
     *
     * <p>The hard bound is the per-observation test because a percentile is a
     * property of a set and cannot be evaluated one row at a time. The
     * distribution target is checked separately, over a window, by
     * {@link #distributionTargetMet}.
     */
    public static boolean breached(Duration internalLatency) {
        return internalLatency.compareTo(HARD_BOUND) > 0;
    }

    /**
     * Whether a lane's measured percentile met what was promised for it.
     *
     * <p>Only the critical lane carries a distribution target. The others are
     * governed by the hard bound alone, which is what the Contract asks for and
     * what an operator can act on: a HIGH answer that arrives in nine minutes is
     * not an incident.
     */
    public static boolean distributionTargetMet(AvailabilityLane lane, Duration measured) {
        if (lane != AvailabilityLane.CRITICAL) {
            return true;
        }
        return measured.compareTo(CRITICAL_DISTRIBUTION_TARGET) <= 0;
    }
}

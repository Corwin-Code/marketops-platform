package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.time.Duration;

/**
 * The internal response bounds the Contract fixes, and the clock they run on.
 *
 * <p>These are constants rather than configuration on purpose. A deployment that
 * could relax its own SLO would report itself healthy by lowering the bar, and
 * the number an operator is being asked to trust would stop meaning anything
 * across environments.
 *
 * <p>The clock starts at {@code fact_accepted_at}, not at the moment a worker
 * picked the work up. Measuring from the worker start would hide exactly the
 * failure this measures — work that sat in a queue.
 */
public final class AdvertisingSlo {

    /** Protection and Regression: the distribution target the P95 must meet. */
    public static final Duration CRITICAL_DISTRIBUTION_TARGET = Duration.ofMinutes(5);

    /** The bound no single targeted path may exceed, whatever its lane. */
    public static final Duration HARD_BOUND = Duration.ofMinutes(15);

    /** The percentile the distribution target applies to. */
    public static final int TARGET_PERCENTILE = 95;

    /** How often the full reconciliation must complete at least once. */
    public static final Duration RECONCILIATION_INTERVAL = Duration.ofHours(1);

    private AdvertisingSlo() {
    }

    /** Whether one internal latency breached the hard acceptance bound. */
    public static boolean breached(Duration internalLatency) {
        return internalLatency != null && internalLatency.compareTo(HARD_BOUND) > 0;
    }

    /** Whether one Protection or Regression latency missed the distribution target. */
    public static boolean missedCriticalTarget(Duration internalLatency) {
        return internalLatency != null
                && internalLatency.compareTo(CRITICAL_DISTRIBUTION_TARGET) > 0;
    }
}

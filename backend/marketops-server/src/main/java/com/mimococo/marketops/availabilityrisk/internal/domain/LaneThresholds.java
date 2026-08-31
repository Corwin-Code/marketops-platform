package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Turns days of cover into a lane, using only the published policy.
 *
 * <p>There are no invented constants here. Every boundary is a value somebody
 * published and owns:
 *
 * <pre>
 *   cover &lt;= safety days                     CRITICAL
 *   safety days &lt; cover &lt;= lead time          HIGH
 *   lead time &lt; cover &lt;= lead time + safety   WATCH
 *   cover &gt; lead time + safety                HEALTHY
 * </pre>
 *
 * <p>Read as sentences: inside the safety buffer is an emergency; ordering now
 * and still running out before it lands is material; landing but eating the
 * buffer is worth watching; anything further out is fine. A hard-coded "seven
 * days is critical" would mean something different for a variant with a
 * three-day lead time and one with a ninety-day lead time.
 */
public final class LaneThresholds {

    private LaneThresholds() {
    }

    /**
     * The lane for a cover figure.
     *
     * <p>A {@code null} cover means demand is positive-free — nothing is
     * selling, so nothing runs out. That is healthy on the availability
     * question; whether a variant nobody buys deserves attention is a different
     * product question and not this Slice's.
     */
    public static AvailabilityLane laneFor(BigDecimal coverDays, LeadTimeResolution leadTime) {
        if (!leadTime.resolved()) {
            throw new IllegalStateException("a lane cannot be derived from a blocked policy");
        }
        if (coverDays == null) {
            return AvailabilityLane.HEALTHY;
        }
        BigDecimal safety = BigDecimal.valueOf(leadTime.safetyDays());
        BigDecimal lead = BigDecimal.valueOf(leadTime.leadTimeDaysMax());
        BigDecimal horizon = BigDecimal.valueOf(leadTime.coverageHorizonDays());

        if (coverDays.compareTo(safety) <= 0) {
            return AvailabilityLane.CRITICAL;
        }
        if (coverDays.compareTo(lead) <= 0) {
            return AvailabilityLane.HIGH;
        }
        if (coverDays.compareTo(horizon) <= 0) {
            return AvailabilityLane.WATCH;
        }
        return AvailabilityLane.HEALTHY;
    }

    /** When cover runs out, or {@code null} when it does not. */
    public static Instant stockoutAt(BigDecimal coverDays, Instant asOf) {
        if (coverDays == null) {
            return null;
        }
        long seconds = coverDays.multiply(BigDecimal.valueOf(86400))
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        return asOf.plus(Duration.ofSeconds(seconds));
    }
}

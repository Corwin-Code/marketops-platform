package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * One D7/D14/D30 window, and how much of it was actually observable.
 *
 * <p>Coverage is the whole point. A week in which a listing was unsellable for
 * six days sold almost nothing, and reading that as "demand is almost nothing"
 * is the mistake this record exists to prevent. The window carries what it saw,
 * how long it could see, and whether that was enough.
 *
 * @param window which window this is
 * @param periodStart inclusive start
 * @param periodEnd exclusive end
 * @param units units observed in the basis the policy names, or {@code null} when no source answered
 * @param observedDays days on which the listing could actually sell
 * @param censoringReason why observation was incomplete, or {@code null}
 * @param largestSingleDayShare the biggest share one day contributed, or {@code null}
 * @param basis which unit {@code units} counts
 * @param observationBegan when stock and sellability were first both stated for the subject, when
 *        that happened inside this window; {@code null} when they were stated from its start, or
 *        never
 */
public record DemandWindowEvidence(
        DemandWindow window,
        Instant periodStart,
        Instant periodEnd,
        Integer units,
        BigDecimal observedDays,
        CensoringReason censoringReason,
        BigDecimal largestSingleDayShare,
        DemandSource basis,
        Instant observationBegan) {

    private static final MathContext RATE_CONTEXT = new MathContext(12, RoundingMode.HALF_UP);

    public DemandWindowEvidence {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        Objects.requireNonNull(observedDays, "observedDays");
        Objects.requireNonNull(basis, "basis");
        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("periodEnd must be after periodStart");
        }
        if (observedDays.signum() < 0) {
            throw new IllegalArgumentException("observedDays cannot be negative");
        }
        if (units != null && units < 0) {
            throw new IllegalArgumentException("units cannot be negative");
        }
    }

    /** Why a window could not be fully observed. */
    public enum CensoringReason {
        /** The listing was not sellable for part of the window. */
        NOT_SELLABLE,
        /** There was nothing in stock to sell for part of the window. */
        NO_STOCK,
        /** The source stopped publishing, so the period is unobserved rather than empty. */
        SOURCE_STALE,
        /** A recorded platform or data outage covered part of the window. */
        KNOWN_OUTAGE,
        /** Observation exists but does not span the whole window. */
        PARTIAL_COVERAGE
    }

    /** The share of the window that could actually be observed, 0..1. */
    public BigDecimal coverageRatio() {
        BigDecimal length = BigDecimal.valueOf(
                java.time.Duration.between(periodStart, periodEnd).toMinutes())
                .divide(BigDecimal.valueOf(1440), RATE_CONTEXT);
        if (length.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = observedDays.divide(length, RATE_CONTEXT);
        return ratio.min(BigDecimal.ONE).max(BigDecimal.ZERO);
    }

    /**
     * Units per observable day, or {@code null} when nothing can be said.
     *
     * <p>The divisor is observed days, not window length. Dividing four units
     * sold in the one day a listing was available by seven days would report a
     * demand rate six sevenths lower than the truth.
     */
    public BigDecimal dailyRate() {
        if (units == null || observedDays.signum() <= 0) {
            return null;
        }
        return BigDecimal.valueOf(units).divide(observedDays, RATE_CONTEXT);
    }

    /** Whether any source answered for this window at all. */
    public boolean observed() {
        return units != null;
    }

    /** Whether observation was materially incomplete. */
    public boolean censored() {
        return censoringReason != null;
    }
}

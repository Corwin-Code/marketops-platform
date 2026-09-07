package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * The set of bid values a platform will actually accept.
 *
 * <p>A marketplace does not accept an arbitrary number. It accepts multiples of
 * a step, within a minimum and a maximum, at a fixed precision, counted in
 * either major or minor currency units. A product that sends a value off that
 * grid gets one of three answers — a refusal, a silent round, or an acceptance
 * that reads back differently — and the second is the dangerous one, because it
 * changes a real bid to a number nobody chose.
 *
 * <p>So normalization happens here, before the value is ever written down, and
 * it always rounds toward the safer side of the intent. A decrease that cannot
 * land exactly lands lower; an increase that cannot land exactly lands lower
 * too. Rounding an increase up would spend more than the calculation justified,
 * and rounding a decrease up would spend more than the protection intended.
 *
 * <p>An unusable grid produces no value at all. A missing step or an absent
 * minimum is not a licence to send the raw number; it means this platform's bid
 * semantics are not known well enough to write one.
 */
public record ProviderBidGrid(
        String bidUnitCode,
        String bidCurrencyCode,
        Integer precision,
        BigDecimal step,
        BigDecimal minimum,
        BigDecimal maximum,
        boolean bidFieldPresent,
        String verificationState) {

    /** The scale every stored bid amount uses, matching the numeric columns. */
    private static final int STORED_SCALE = 4;

    public ProviderBidGrid {
        Objects.requireNonNull(bidUnitCode, "bidUnitCode");
    }

    /**
     * Whether this grid can describe a write at all.
     *
     * <p>Verification is part of the question. A profile somebody typed in from
     * a blog post describes a grid; it does not describe a grid this product may
     * act on.
     */
    public boolean usable() {
        return bidFieldPresent
                && java.util.Set.of("CURRENCY_MAJOR","CURRENCY_MINOR").contains(bidUnitCode)
                && "VERIFIED".equals(verificationState)
                && step != null && step.signum() > 0
                && minimum != null && minimum.signum() > 0
                && maximum != null && maximum.compareTo(minimum) >= 0
                && precision != null && precision >= 0 && precision <= STORED_SCALE;
    }

    /**
     * The nearest acceptable value at or below the request.
     *
     * <p>Empty when the grid is unusable, when the request is below the platform
     * minimum, or when rounding down would leave nothing. Never above the
     * request, never above the maximum, and never off the step.
     */
    public Optional<BigDecimal> normalizeDownward(BigDecimal requested) {
        if (requested == null || !usable() || requested.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal capped = requested.min(maximum);
        if (capped.compareTo(minimum) < 0) {
            // Below what the platform accepts. Sending the minimum instead would
            // be sending a number the calculation did not ask for.
            return Optional.empty();
        }
        BigDecimal offset = capped.subtract(minimum);
        BigDecimal steps = offset.divide(step, 0, RoundingMode.FLOOR);
        BigDecimal landed = minimum.add(steps.multiply(step))
                .setScale(STORED_SCALE, RoundingMode.UNNECESSARY);
        if (landed.compareTo(minimum) < 0 || landed.compareTo(maximum) > 0
                || landed.compareTo(capped) > 0) {
            return Optional.empty();
        }
        if (landed.stripTrailingZeros().scale() > precision) {
            // The step and the precision disagree. Rather than pick one, refuse:
            // a value this product cannot represent exactly is a value it cannot
            // verify on readback either.
            return Optional.empty();
        }
        return Optional.of(landed);
    }
}

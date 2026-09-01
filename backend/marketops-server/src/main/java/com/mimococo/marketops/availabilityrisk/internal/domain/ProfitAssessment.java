package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.ProfitLane;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Which profit authority spoke for this variant, and what it said.
 *
 * <p>The lane is chosen from the strongest available authority, never blended.
 * A settled figure and an estimate are different kinds of claim, and averaging
 * them would produce a number that is neither.
 *
 * @param lane the eligibility lane
 * @param perUnitAmount contribution profit per unit, or {@code null}
 * @param currencyCode the currency of {@code perUnitAmount}, or {@code null}
 * @param metricValueId the metric row that answered, or {@code null}
 * @param reason a short, stable explanation
 */
public record ProfitAssessment(
        ProfitLane lane,
        BigDecimal perUnitAmount,
        String currencyCode,
        UUID metricValueId,
        String reason) {

    public ProfitAssessment {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(reason, "reason");
        if ((perUnitAmount == null) != (currencyCode == null)) {
            throw new IllegalArgumentException("an amount and its currency travel together");
        }
    }

    /** Nothing answered. */
    public static ProfitAssessment unknown(String reason) {
        return new ProfitAssessment(ProfitLane.PROFIT_UNKNOWN, null, null, null, reason);
    }

    /**
     * Total contribution profit exposed by {@code units} being unavailable.
     *
     * <p>Returns {@code null} rather than zero when no amount is known: zero
     * profit at risk and unknown profit at risk rank very differently, and a
     * rank that cannot tell them apart buries the unknown ones.
     */
    public BigDecimal exposureFor(BigDecimal units) {
        if (perUnitAmount == null || units == null) {
            return null;
        }
        return perUnitAmount.multiply(units);
    }
}

package com.mimococo.marketops.operationsworkflow;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * What a price change would do, computed from current canonical facts.
 *
 * <p>The preview is built from the same values the guardrail reads and carries
 * its verdict, so what an operator sees before approving is what will be
 * checked at the moment of the write. A preview that used different numbers
 * from the gate would be a way to approve something that then refuses, or
 * worse, to approve something the gate would have refused.
 *
 * <p>Every amount can be absent. A missing unit cost does not become a zero:
 * the projected profit is simply unavailable, and the verdict says why.
 *
 * @param recommendationId the proposal being previewed
 * @param currencyCode currency of every amount here
 * @param currentPrice the price currently observed on the platform, or {@code null}
 * @param proposedPrice the price the recommendation proposes
 * @param changeRate the proportional change, or {@code null} without a current price
 * @param breakEvenPrice the price below which the unit loses money, or {@code null}
 * @param currentUnitProfit unit contribution profit at the current price, or {@code null}
 * @param projectedUnitProfit unit contribution profit at the proposed price, or {@code null}
 * @param currentMargin contribution margin now, or {@code null}
 * @param projectedMargin contribution margin at the proposed price, or {@code null}
 * @param verdict the deterministic guardrail decision
 */
public record ImpactPreview(
        UUID recommendationId,
        String currencyCode,
        BigDecimal currentPrice,
        BigDecimal proposedPrice,
        BigDecimal changeRate,
        BigDecimal breakEvenPrice,
        BigDecimal currentUnitProfit,
        BigDecimal projectedUnitProfit,
        BigDecimal currentMargin,
        BigDecimal projectedMargin,
        GuardrailVerdict verdict) {

    public ImpactPreview {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(verdict, "verdict");
    }

    /** Whether the projection could be computed at all. */
    public boolean projectionAvailable() {
        return projectedUnitProfit != null;
    }
}

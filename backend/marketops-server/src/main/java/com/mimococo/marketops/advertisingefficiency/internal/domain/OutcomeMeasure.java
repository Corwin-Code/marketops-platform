package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * The measures an Outcome Evaluation Plan may name, and which way is better.
 *
 * <p>Direction of goodness belongs to the measure rather than to the policy row.
 * Spending less is an improvement and earning less is not, and a policy flag
 * that could say otherwise would let somebody configure a plan under which
 * halving revenue counted as a success.
 *
 * <p>A plan naming a measure this product cannot compute resolves to nothing.
 * That is a refusal, not a zero: an outcome computed from a metric nobody
 * implemented would be a number with no meaning, and it would compare cleanly
 * against a threshold anyway.
 */
public enum OutcomeMeasure {

    /**
     * Official spend over the window. Lower is better, which is what a
     * Protection decrease is trying to achieve.
     */
    AD_SPEND(false),

    /**
     * Deterministically linked net sales at the plan's stage. Higher is better.
     */
    AD_LINKED_NET_SALES(true),

    /**
     * Contribution profit the advertising produced. Higher is better.
     */
    ADVERTISING_CONTRIBUTION_PROFIT(true),

    /**
     * Contribution profit per rouble of advertising. Higher is better, and it is
     * the measure that survives a campaign simply being made bigger or smaller.
     */
    CONTRIBUTION_PROFIT_PER_AD_RUB(true);

    private static final MathContext CONTEXT = new MathContext(20, RoundingMode.HALF_UP);
    private static final int MONEY_SCALE = 4;
    private static final int RATIO_SCALE = 6;

    private final boolean higherIsBetter;

    OutcomeMeasure(boolean higherIsBetter) {
        this.higherIsBetter = higherIsBetter;
    }

    /** Whether a rise in this measure is the improvement the plan is looking for. */
    public boolean higherIsBetter() {
        return higherIsBetter;
    }

    /** The measure a plan's metric code names, if this product computes it. */
    public static Optional<OutcomeMeasure> of(String metricCode) {
        for (OutcomeMeasure measure : values()) {
            if (measure.name().equals(metricCode)) {
                return Optional.of(measure);
            }
        }
        return Optional.empty();
    }

    /**
     * Compute this measure from one window's official facts and linked sales.
     *
     * <p>Every absence stays an absence. A window with spend but no linked sales
     * yields no profit measure rather than a profit of minus the spend, because
     * "we could not link the sales" and "there were no sales" are different
     * statements and only the second is a result.
     */
    public AdMeasure compute(WindowFacts facts) {
        if (facts == null) {
            return AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE);
        }
        AdEvidenceState evidence = facts.evidenceState();
        return switch (this) {
            case AD_SPEND -> money(facts.spendAmount(), evidence);
            case AD_LINKED_NET_SALES -> money(facts.linkedNetSales(), evidence);
            case ADVERTISING_CONTRIBUTION_PROFIT -> {
                if (facts.linkedContributionProfit() == null) {
                    yield AdMeasure.notAvailable(evidence);
                }
                yield money(facts.linkedContributionProfit(), evidence);
            }
            case CONTRIBUTION_PROFIT_PER_AD_RUB -> {
                BigDecimal profit = facts.linkedContributionProfit();
                BigDecimal spend = facts.spendAmount();
                if (profit == null || spend == null) {
                    yield AdMeasure.notAvailable(evidence);
                }
                if (spend.signum() == 0) {
                    // No advertising rouble was spent, so profit per advertising
                    // rouble is undefined rather than infinite or zero.
                    yield AdMeasure.undefined(evidence);
                }
                yield AdMeasure.available(
                        profit.divide(spend, CONTEXT).setScale(RATIO_SCALE, RoundingMode.HALF_UP),
                        evidence);
            }
        };
    }

    private static AdMeasure money(BigDecimal amount, AdEvidenceState evidence) {
        return amount == null
                ? AdMeasure.notAvailable(evidence)
                : AdMeasure.available(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP), evidence);
    }

    /**
     * What one window of the ledger says.
     *
     * <p>Deliberately a value rather than a query result. The baseline window and
     * the observation window are read the same way, so a difference between them
     * is a difference in the world rather than in how they were fetched.
     *
     * @param spendAmount official advertising spend, or {@code null}
     * @param clicks eligible traffic, or {@code null}
     * @param linkedNetSales deterministically linked net sales, or {@code null}
     * @param linkedContributionProfit contribution profit from those sales, or {@code null}
     * @param evidenceState how good the weakest input behind these is
     */
    public record WindowFacts(
            BigDecimal spendAmount,
            Long clicks,
            BigDecimal linkedNetSales,
            BigDecimal linkedContributionProfit,
            AdEvidenceState evidenceState) {

        public WindowFacts {
            if (evidenceState == null) {
                evidenceState = AdEvidenceState.NOT_AVAILABLE;
            }
        }

        /** A window nothing was recorded for. */
        public static WindowFacts absent() {
            return new WindowFacts(null, null, null, null, AdEvidenceState.NOT_AVAILABLE);
        }
    }
}

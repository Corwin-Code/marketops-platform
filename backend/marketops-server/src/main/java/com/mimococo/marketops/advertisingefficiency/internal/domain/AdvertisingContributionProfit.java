package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What one advertising object actually earned, after everything it costs.
 *
 * <p>The arithmetic is deliberately boring; what matters is which numbers are
 * allowed into it. The variable economics are applied <em>per ad-linked unit</em>
 * rather than allocated from a period total, because the unit count is itself an
 * ad-linked fact. That removes the allocation step entirely, and with it the
 * class of error where a SKU-level estimate quietly becomes an object-level
 * profit claim.
 *
 * <p>Every component must be present and good enough for the purpose, or the
 * result is {@code NOT_AVAILABLE} naming what was missing. There is no partial
 * profit: a contribution figure computed without return loss is not a
 * conservative contribution figure, it is a wrong one in the direction of
 * spending more.
 *
 * <p>The production line calculator consumes the canonical version 2 fee
 * metric, including fulfillment and promotion costs, once per actual unit.
 * A confirmed zero is distinct from missing economic evidence; the latter
 * blocks the affected contribution instead of silently dropping a component.
 */
public record AdvertisingContributionProfit(
        AdMeasure absoluteProfit,
        AdMeasure profitPerAdRub,
        String currencyCode,
        List<String> missingComponentCodes) {

    private static final MathContext CONTEXT = new MathContext(20, RoundingMode.HALF_UP);
    private static final int MONEY_SCALE = 4;
    private static final int RATIO_SCALE = 6;

    /** The inputs, each of which may legitimately be absent. */
    public record Components(
            AdMeasure attributableNetSales,
            long adLinkedUnits,
            AdMeasure unitCost,
            AdMeasure platformFeesPerUnit,
            AdMeasure returnLossPerUnit,
            AdMeasure promotionCostPerUnit,
            AdMeasure variableTaxPerUnit,
            AdMeasure officialAdSpend,
            String currencyCode) {

        public Components {
            Objects.requireNonNull(currencyCode, "currencyCode");
            if (adLinkedUnits < 0) {
                throw new IllegalArgumentException("ad-linked units cannot be negative");
            }
        }
    }

    public AdvertisingContributionProfit {
        Objects.requireNonNull(absoluteProfit, "absoluteProfit");
        Objects.requireNonNull(profitPerAdRub, "profitPerAdRub");
        missingComponentCodes =
                List.copyOf(Objects.requireNonNull(missingComponentCodes, "missingComponentCodes"));
    }

    /**
     * Compute both axes, or say which component stopped them.
     *
     * <p>The per-rouble axis is separately {@code UNDEFINED} when spend is zero
     * rather than absent. Dividing by zero spend would be an infinite return,
     * which is arithmetically true and operationally meaningless: an object that
     * spent nothing did not earn its profit from advertising.
     */
    public static AdvertisingContributionProfit compute(Components components) {
        Objects.requireNonNull(components, "components");
        List<String> missing = new ArrayList<>();
        require(missing, "ATTRIBUTABLE_NET_SALES", components.attributableNetSales());
        require(missing, "UNIT_COST", components.unitCost());
        require(missing, "PLATFORM_FEES_PER_UNIT", components.platformFeesPerUnit());
        require(missing, "RETURN_LOSS_PER_UNIT", components.returnLossPerUnit());
        require(missing, "PROMOTION_COST_PER_UNIT", components.promotionCostPerUnit());
        require(missing, "VARIABLE_TAX_PER_UNIT", components.variableTaxPerUnit());
        require(missing, "OFFICIAL_AD_SPEND", components.officialAdSpend());

        if (!missing.isEmpty()) {
            return blocked(components.currencyCode(), List.copyOf(missing));
        }

        BigDecimal perUnitCost = components.unitCost().value()
                .add(components.platformFeesPerUnit().value())
                .add(components.returnLossPerUnit().value())
                .add(components.promotionCostPerUnit().value())
                .add(components.variableTaxPerUnit().value());
        BigDecimal variableCost = perUnitCost
                .multiply(BigDecimal.valueOf(components.adLinkedUnits()), CONTEXT);
        BigDecimal spend = components.officialAdSpend().value();
        BigDecimal absolute = components.attributableNetSales().value()
                .subtract(variableCost)
                .subtract(spend)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // The weakest component decides how much the answer may be trusted. A
        // profit built from one estimated input is an estimated profit.
        AdEvidenceState evidence = weakest(components);

        AdMeasure absoluteMeasure = AdMeasure.available(absolute, evidence);
        AdMeasure perRub = spend.signum() <= 0
                ? AdMeasure.undefined(evidence)
                : AdMeasure.available(
                        absolute.divide(spend, RATIO_SCALE, RoundingMode.HALF_UP), evidence);
        return new AdvertisingContributionProfit(
                absoluteMeasure, perRub, components.currencyCode(), List.of());
    }

    /** No profit could be computed, and these are the components that stopped it. */
    public static AdvertisingContributionProfit blocked(
            String currencyCode, List<String> missingComponentCodes) {
        return new AdvertisingContributionProfit(
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED),
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED),
                currencyCode, missingComponentCodes);
    }

    /** Whether both axes carry a number. */
    public boolean resolved() {
        return absoluteProfit.present();
    }

    /**
     * Whether the result is a loss.
     *
     * <p>Used by the lane resolver to decide whether continuing spend is proven
     * harm. An unresolved profit is not a loss — that asymmetry is the point.
     */
    public boolean provenLoss() {
        return absoluteProfit.present() && absoluteProfit.value().signum() < 0;
    }

    private static void require(List<String> missing, String code, AdMeasure measure) {
        if (measure == null || !measure.present()) {
            missing.add(code);
        }
    }

    private static AdEvidenceState weakest(Components components) {
        AdEvidenceState weakest = components.attributableNetSales().evidenceState();
        for (AdMeasure measure : List.of(
                components.unitCost(), components.platformFeesPerUnit(),
                components.returnLossPerUnit(), components.promotionCostPerUnit(),
                components.variableTaxPerUnit(), components.officialAdSpend())) {
            weakest = weakest.weakest(measure.evidenceState());
        }
        return weakest;
    }
}

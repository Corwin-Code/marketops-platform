package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What an advertising object earned, and every reason it might not be knowable. */
class AdvertisingContributionProfitTest {

    private static AdMeasure at(String value) {
        return AdMeasure.available(new BigDecimal(value), AdEvidenceState.CANONICAL_CONFIRMED);
    }

    private static AdvertisingContributionProfit.Components components(
            String netSales, long units, String spend) {
        return new AdvertisingContributionProfit.Components(
                at(netSales), units, at("100"), at("30"), at("10"), at("0"), at("20"),
                at(spend), "RUB");
    }

    @Test
    @DisplayName("TC-ADV-PROFIT-001 profit is net sales less per-unit economics less official spend")
    void profitSubtractsPerUnitEconomicsAndSpend() {
        // 10 units at 160 of variable cost each is 1600; 5000 - 1600 - 900 = 2500.
        AdvertisingContributionProfit profit =
                AdvertisingContributionProfit.compute(components("5000", 10, "900"));

        assertThat(profit.resolved()).isTrue();
        assertThat(profit.absoluteProfit().value()).isEqualByComparingTo("2500.0000");
        assertThat(profit.profitPerAdRub().value())
                .isEqualByComparingTo(new BigDecimal("2500").divide(new BigDecimal("900"), 6,
                        java.math.RoundingMode.HALF_UP));
        assertThat(profit.currencyCode()).isEqualTo("RUB");
    }

    @Test
    @DisplayName("TC-ADV-PROFIT-002 a loss is reported as a loss rather than clamped")
    void lossIsReportedAsALoss() {
        AdvertisingContributionProfit profit =
                AdvertisingContributionProfit.compute(components("500", 10, "900"));

        assertThat(profit.absoluteProfit().value()).isEqualByComparingTo("-2000.0000");
        assertThat(profit.provenLoss()).isTrue();
    }

    @Test
    @DisplayName("TC-ADV-PROFIT-003 zero spend makes the per-rouble axis undefined, not infinite")
    void zeroSpendMakesThePerRoubleAxisUndefined() {
        AdvertisingContributionProfit profit =
                AdvertisingContributionProfit.compute(components("5000", 10, "0"));

        assertThat(profit.absoluteProfit().present()).isTrue();
        assertThat(profit.profitPerAdRub().present()).isFalse();
        assertThat(profit.profitPerAdRub().valueState().name()).isEqualTo("UNDEFINED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATTRIBUTABLE_NET_SALES", "UNIT_COST", "PLATFORM_FEES_PER_UNIT",
            "RETURN_LOSS_PER_UNIT", "PROMOTION_COST_PER_UNIT", "VARIABLE_TAX_PER_UNIT",
            "OFFICIAL_AD_SPEND"})
    @DisplayName("TC-ADV-PROFIT-004 any missing component blocks the whole profit and names itself")
    void anyMissingComponentBlocksTheProfit(String missing) {
        AdMeasure absent = AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED);
        var components = new AdvertisingContributionProfit.Components(
                missing.equals("ATTRIBUTABLE_NET_SALES") ? absent : at("5000"), 10,
                missing.equals("UNIT_COST") ? absent : at("100"),
                missing.equals("PLATFORM_FEES_PER_UNIT") ? absent : at("30"),
                missing.equals("RETURN_LOSS_PER_UNIT") ? absent : at("10"),
                missing.equals("PROMOTION_COST_PER_UNIT") ? absent : at("0"),
                missing.equals("VARIABLE_TAX_PER_UNIT") ? absent : at("20"),
                missing.equals("OFFICIAL_AD_SPEND") ? absent : at("900"),
                "RUB");

        AdvertisingContributionProfit profit = AdvertisingContributionProfit.compute(components);

        assertThat(profit.resolved()).isFalse();
        assertThat(profit.provenLoss()).isFalse();
        assertThat(profit.missingComponentCodes()).containsExactly(missing);
        assertThat(profit.absoluteProfit().evidenceState()).isEqualTo(AdEvidenceState.DATA_BLOCKED);
    }

    @Test
    @DisplayName("TC-ADV-PROFIT-005 a declared zero promotion cost is a value, an absent one is not")
    void declaredZeroPromotionIsDifferentFromAbsentPromotion() {
        AdvertisingContributionProfit declared =
                AdvertisingContributionProfit.compute(components("5000", 10, "900"));
        var withoutDeclaration = new AdvertisingContributionProfit.Components(
                at("5000"), 10, at("100"), at("30"), at("10"),
                AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE), at("20"), at("900"), "RUB");

        assertThat(declared.resolved()).isTrue();
        assertThat(AdvertisingContributionProfit.compute(withoutDeclaration).resolved()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-PROFIT-006 the weakest component decides how much the answer may be trusted")
    void weakestComponentDecidesTheEvidenceState() {
        var withEstimate = new AdvertisingContributionProfit.Components(
                at("5000"), 10, at("100"), at("30"),
                AdMeasure.available(new BigDecimal("10"),
                        AdEvidenceState.PROVISIONAL_OR_ESTIMATED),
                at("0"), at("20"), at("900"), "RUB");

        AdvertisingContributionProfit profit = AdvertisingContributionProfit.compute(withEstimate);

        assertThat(profit.resolved()).isTrue();
        assertThat(profit.absoluteProfit().evidenceState())
                .isEqualTo(AdEvidenceState.PROVISIONAL_OR_ESTIMATED);
        assertThat(profit.absoluteProfit().sufficientForWrite()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-PROFIT-007 an unresolved profit is not a loss")
    void unresolvedProfitIsNotALoss() {
        AdvertisingContributionProfit blocked =
                AdvertisingContributionProfit.blocked("RUB", java.util.List.of("UNIT_COST"));

        assertThat(blocked.resolved()).isFalse();
        assertThat(blocked.provenLoss()).isFalse();
    }
}

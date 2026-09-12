package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.shared.Money;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromotionSimulatorTest {
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static Money rub(String value) { return Money.of(decimal(value), "RUB"); }
    private static PromotionSimulator.Expenses expenses(String fixed) {
        return new PromotionSimulator.Expenses(rub(fixed), rub("0"), rub("0"), rub("0"));
    }
    private static PromotionSimulator.Inputs complete() {
        return inputs("1000", "0.1", "500", List.of(new PromotionSimulator.FeeStep(decimal("0"), decimal("100")),
                new PromotionSimulator.FeeStep(decimal("800"), decimal("150"))), expenses("0"));
    }
    private static PromotionSimulator.Inputs inputs(String price, String discount, String cost,
                                                     List<PromotionSimulator.FeeStep> fees, PromotionSimulator.Expenses expenses) {
        return new PromotionSimulator.Inputs(decimal(price), decimal(discount), false, decimal(cost), fees, true, "RUB", expenses);
    }
    private static PromotionSimulator.Scenario scenario(String code, String quantity, boolean conservative) {
        return new PromotionSimulator.Scenario(code, decimal(quantity), true, conservative);
    }

    @Test
    void completeConditionalScenarioAndInverseUseSameProfit() {
        var result = PromotionSimulator.simulate(complete(), List.of(scenario("BASE", "10", true)), decimal("2000"));
        assertThat(result.scenarios().getFirst().netRevenue()).isEqualByComparingTo("9000");
        assertThat(result.scenarios().getFirst().contributionProfit()).isEqualByComparingTo("2500");
        assertThat(result.conditionalScenariosPassed()).isTrue();
        assertThat(result.inverseMinimumQuantity()).isEqualByComparingTo("8");
    }

    @Test
    void highestApplicableFloorWinsEvenWhenItsFeeDecreases() {
        var input = inputs("200", "0", "100", List.of(new PromotionSimulator.FeeStep(decimal("100"), decimal("5")),
                new PromotionSimulator.FeeStep(decimal("0"), decimal("20"))), expenses("0"));
        var result = PromotionSimulator.simulate(input, List.of(scenario("P04", "1", true)), decimal("95"));
        assertThat(result.scenarios().getFirst().contributionProfit()).isEqualByComparingTo("95");
        assertThat(result.inverseMinimumQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void nonConservativeNecessaryScenarioCannotPass() {
        var result = PromotionSimulator.simulate(complete(), List.of(scenario("P05", "10", false)), decimal("1"));
        assertThat(result.scenarios().getFirst().state()).isEqualTo("COMPUTED");
        assertThat(result.conditionalScenariosPassed()).isNull();
    }

    @Test
    void fixedCommitmentIsChargedOnceInBothDirectionsIncludingZeroSales() {
        var input = inputs("100", "0", "50", List.of(new PromotionSimulator.FeeStep(decimal("0"), decimal("0"))), expenses("600"));
        var result = PromotionSimulator.simulate(input, List.of(scenario("ZERO", "0", true), scenario("BELOW", "13", true),
                scenario("MINIMUM", "14", true)), decimal("100"));
        assertThat(result.scenarios()).extracting(r -> r.contributionProfit().toPlainString())
                .containsExactly("-600.0000", "50.0000", "100.0000");
        assertThat(result.inverseMinimumQuantity()).isEqualByComparingTo("14");
        assertThat(result.conditionalScenariosPassed()).isFalse();
    }

    @Test
    void explicitCanonicalExpensesAffectBothDirections() {
        var input = inputs("100", "0", "50", List.of(new PromotionSimulator.FeeStep(decimal("0"), decimal("10"))),
                new PromotionSimulator.Expenses(rub("100"), rub("5"), rub("3"), rub("2")));
        var result = PromotionSimulator.simulate(input, List.of(scenario("ALL_FAMILIES", "10", true)), decimal("200"));
        assertThat(result.scenarios().getFirst().contributionProfit()).isEqualByComparingTo("200");
        assertThat(result.inverseMinimumQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void noApplicableOrEmptyFeeTierIsUnknownEvenWhenCallerSaysKnown() {
        for (var fees : List.of(List.<PromotionSimulator.FeeStep>of(),
                List.of(new PromotionSimulator.FeeStep(decimal("200"), decimal("10"))))) {
            var result = PromotionSimulator.simulate(inputs("100", "0", "50", fees, expenses("0")),
                    List.of(scenario("MISSING", "1", true)), decimal("1"));
            assertThat(result.scenarios().getFirst().missingInputs()).containsExactly("FEE_SCHEDULE");
            assertThat(result.inverseState()).isEqualTo("UNDETERMINED");
            assertThat(result.conditionalScenariosPassed()).isNull();
        }
    }

    @Test
    void missingComponentsAndCurrencyAreNamedWithoutDefaults() {
        var input = new PromotionSimulator.Inputs(decimal("1000"), null, false, null, List.of(), false, null, null);
        var result = PromotionSimulator.simulate(input, List.of(new PromotionSimulator.Scenario("MISSING", null, true, true)), decimal("1"));
        assertThat(result.scenarios().getFirst().missingInputs()).containsExactly("QUANTITY", "CURRENCY", "SELLER_DISCOUNT", "UNIT_COST",
                "FEE_SCHEDULE", "FIXED_PROMOTION_FEE", "RETURN_LOSS", "ADVERTISING", "VARIABLE_TAX");
        assertThat(result.scenarios().getFirst().contributionProfit()).isNull();
        assertThat(result.inverseState()).isEqualTo("UNDETERMINED");
    }

    @Test
    void netRevenueDoesNotDeductCarriedSellerDiscountTwice() {
        var base = complete();
        var carried = new PromotionSimulator.Inputs(decimal("900"), decimal("0.1"), true, base.unitCost(),
                base.stepFees(), true, "RUB", base.expenses());
        assertThat(PromotionSimulator.simulate(carried, List.of(scenario("NET", "10", true)), decimal("2000")))
                .isEqualTo(PromotionSimulator.simulate(base, List.of(scenario("NET", "10", true)), decimal("2000")));
    }

    @Test
    void canonicalHalfUpRoundingIsUsedBeforeIntegerQuantityMultiplication() {
        var input = inputs("1.00005", "0", "0", List.of(new PromotionSimulator.FeeStep(decimal("0"), decimal("0"))), expenses("0"));
        var result = PromotionSimulator.simulate(input, List.of(scenario("ROUND", "2", true)), decimal("2.0002"));
        assertThat(result.scenarios().getFirst().contributionProfit()).isEqualByComparingTo("2.0002");
        assertThat(result.inverseMinimumQuantity()).isEqualByComparingTo("2");
    }

    @Test
    void referenceUsesSameMoneyScaleInForwardComparisonAndInverse() {
        var input = inputs("1", "0", "0", List.of(new PromotionSimulator.FeeStep(decimal("0"), decimal("0"))), expenses("0"));
        var result = PromotionSimulator.simulate(input, List.of(scenario("REFERENCE", "1", true)), decimal("1.00004"));
        assertThat(result.conditionalScenariosPassed()).isTrue();
        assertThat(result.inverseMinimumQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void zeroSalesCanAlreadyMeetNegativeReferenceEvenWithNonPositiveSlope() {
        var input = inputs("100", "0", "120", List.of(new PromotionSimulator.FeeStep(decimal("0"), decimal("0"))), expenses("600"));
        assertThat(PromotionSimulator.simulate(input, List.of(), decimal("-600")).inverseMinimumQuantity()).isEqualByComparingTo("0");
        assertThat(PromotionSimulator.simulate(input, List.of(), decimal("-599")).inverseState()).isEqualTo("NO_SOLUTION");
    }

    @Test
    void necessaryFailureIsNotHiddenByOtherUnknownOrProfitableScenario() {
        var result = PromotionSimulator.simulate(complete(), List.of(scenario("FAIL", "1", true),
                new PromotionSimulator.Scenario("UNKNOWN", null, true, true),
                new PromotionSimulator.Scenario("UPSIDE", decimal("100"), false, false)), decimal("2600"));
        assertThat(result.conditionalScenariosPassed()).isFalse();
        assertThat(result.inverseMinimumQuantity()).isEqualByComparingTo("11");
    }

    @Test
    void currencyMismatchCannotBeComputedOrConverted() {
        assertThatThrownBy(() -> inputs("100", "0", "50", List.of(),
                new PromotionSimulator.Expenses(Money.of(decimal("1"), "USD"), rub("0"), rub("0"), rub("0"))))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void ambiguousFeesInvalidDiscountNegativeAndFractionalQuantityAreRejected() {
        var duplicates = List.of(new PromotionSimulator.FeeStep(decimal("0"), decimal("1")),
                new PromotionSimulator.FeeStep(decimal("0.0"), decimal("2")));
        assertThatThrownBy(() -> inputs("100", "0", "50", duplicates, expenses("0"))).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> inputs("100", "1.1", "50", List.of(), expenses("0"))).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> scenario("NEGATIVE", "-1", true)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> scenario("FRACTION", "0.1", true)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> PromotionSimulator.simulate(complete(), List.of(scenario("DUP", "1", true),
                scenario("DUP", "2", false)), decimal("0"))).isInstanceOf(OperationRejectedException.class);
    }
}

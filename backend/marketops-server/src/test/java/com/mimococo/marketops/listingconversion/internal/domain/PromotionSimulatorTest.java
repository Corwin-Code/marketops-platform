package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Decimal money, the discount applied once, stepwise fees, and an honest UNDETERMINED. */
class PromotionSimulatorTest {

    private static PromotionSimulator.Inputs complete() {
        return new PromotionSimulator.Inputs(new BigDecimal("1000"), new BigDecimal("0.10"), false,
                new BigDecimal("500"), List.of(new PromotionSimulator.FeeStep(BigDecimal.ZERO, new BigDecimal("100")),
                        new PromotionSimulator.FeeStep(new BigDecimal("800"), new BigDecimal("150"))), true);
    }

    @Test
    @DisplayName("TC-LC-S01 a complete scenario computes net revenue and contribution profit exactly")
    void completeScenarioComputes() {
        var simulation = PromotionSimulator.simulate(complete(),
                List.of(new PromotionSimulator.Scenario("BASE", BigDecimal.TEN, true, false)),
                new BigDecimal("2000"));

        var base = simulation.scenarios().get(0);
        assertThat(base.state()).isEqualTo("COMPUTED");
        assertThat(base.netRevenue()).isEqualByComparingTo("9000.0000");
        assertThat(base.contributionProfit()).isEqualByComparingTo("2500.0000");
        assertThat(simulation.demandGatePassed()).isTrue();
        assertThat(simulation.inverseState()).isEqualTo("COMPUTED");
        assertThat(simulation.inverseMinimumQuantity()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("TC-LC-S02 the discount is applied once, and not at all when the source already carries it")
    void discountAppliedOnce() {
        assertThat(PromotionSimulator.netPrice(complete())).isEqualByComparingTo("900.0000");
        var carried = new PromotionSimulator.Inputs(new BigDecimal("1000"), new BigDecimal("0.10"), true,
                new BigDecimal("500"), List.of(), true);
        assertThat(PromotionSimulator.netPrice(carried)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("TC-LC-S03 a missing cost or fee schedule is UNDETERMINED with the missing inputs named")
    void missingInputsAreNamed() {
        var noCost = new PromotionSimulator.Inputs(new BigDecimal("1000"), null, false, null, List.of(), false);

        var simulation = PromotionSimulator.simulate(noCost,
                List.of(new PromotionSimulator.Scenario("BASE", BigDecimal.TEN, true, false),
                        new PromotionSimulator.Scenario("OPEN", null, false, false)),
                new BigDecimal("2000"));

        assertThat(simulation.scenarios().get(0).state()).isEqualTo("UNDETERMINED");
        assertThat(simulation.scenarios().get(0).missingInputs()).containsExactly("UNIT_COST", "FEE_SCHEDULE");
        assertThat(simulation.scenarios().get(1).missingInputs()).containsExactly("QUANTITY", "UNIT_COST",
                "FEE_SCHEDULE");
        assertThat(simulation.demandGatePassed()).isNull();
        assertThat(simulation.inverseState()).isEqualTo("UNDETERMINED");
    }

    @Test
    @DisplayName("TC-LC-S04 a necessary scenario below the reference line fails the demand gate")
    void necessaryScenarioBelowTheLineFails() {
        var simulation = PromotionSimulator.simulate(complete(),
                List.of(new PromotionSimulator.Scenario("BASE", BigDecimal.TEN, true, false),
                        new PromotionSimulator.Scenario("UPSIDE", new BigDecimal("100"), false, false)),
                new BigDecimal("2600"));

        assertThat(simulation.demandGatePassed()).isFalse();
        assertThat(simulation.inverseMinimumQuantity()).isEqualByComparingTo("11");
    }

    @Test
    @DisplayName("TC-LC-S05 a non-positive unit profit has no inverse solution")
    void nonPositiveProfitHasNoSolution() {
        var losing = new PromotionSimulator.Inputs(new BigDecimal("1000"), new BigDecimal("0.10"), false,
                new BigDecimal("1000"), List.of(), true);

        var simulation = PromotionSimulator.simulate(losing, List.of(), new BigDecimal("1"));

        assertThat(simulation.inverseState()).isEqualTo("NO_SOLUTION");
        assertThat(simulation.inverseMinimumQuantity()).isNull();
        assertThat(simulation.demandGatePassed()).isNull();
    }
}

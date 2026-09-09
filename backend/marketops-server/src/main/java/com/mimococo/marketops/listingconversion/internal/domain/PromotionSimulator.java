package com.mimococo.marketops.listingconversion.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Forward scenarios and the inverse minimum quantity from the same profit inputs.
 *
 * <p>Step fees stay stepwise, a seller discount already inside net revenue is
 * not deducted again, a missing fee stays missing, and an answer that cannot
 * be computed is NO_SOLUTION or UNDETERMINED rather than a fabricated number.
 * Demand gating requires every necessary conservative scenario to pass on its
 * own.
 */
public final class PromotionSimulator {

    private static final int SCALE = 4;

    private PromotionSimulator() {
    }

    /** One fee step: applies to the unit when the net price is at or above the floor. */
    public record FeeStep(BigDecimal priceFloor, BigDecimal feePerUnit) {
    }

    /**
     * The profit inputs of one listing under one promotion.
     *
     * @param listPrice the price before the promotion
     * @param sellerDiscountRate the seller's discount, 0..1, or {@code null} when none
     * @param discountAlreadyInNetRevenue whether the source net revenue already carries the discount
     * @param unitCost the unit cost, or {@code null} when unknown
     * @param stepFees the platform's stepwise fees, empty when unknown
     * @param feesKnown whether the fee schedule is complete
     */
    public record Inputs(BigDecimal listPrice, BigDecimal sellerDiscountRate, boolean discountAlreadyInNetRevenue,
                         BigDecimal unitCost, List<FeeStep> stepFees, boolean feesKnown) {
        public Inputs {
            Objects.requireNonNull(listPrice, "listPrice");
            stepFees = List.copyOf(stepFees == null ? List.of() : stepFees);
        }
    }

    /** One scenario: a demand assumption with whether it is necessary for the gate. */
    public record Scenario(String code, BigDecimal quantity, boolean necessary, boolean conservative) {
    }

    /** A scenario's answer. */
    public record ScenarioResult(String code, String state, BigDecimal quantity, BigDecimal netRevenue,
                                 BigDecimal contributionProfit, List<String> missingInputs) {
        public ScenarioResult {
            missingInputs = List.copyOf(missingInputs == null ? List.of() : missingInputs);
        }
    }

    public record Simulation(List<ScenarioResult> scenarios, BigDecimal inverseMinimumQuantity,
                             String inverseState, Boolean demandGatePassed) {
        public Simulation {
            scenarios = List.copyOf(scenarios);
        }
    }

    public static Simulation simulate(Inputs inputs, List<Scenario> scenarios, BigDecimal referenceProfitLine) {
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            results.add(scenarioResult(inputs, scenario));
        }
        BigDecimal unitProfit = unitProfit(inputs);
        BigDecimal minimum = null;
        String inverseState;
        if (unitProfit == null || referenceProfitLine == null) {
            inverseState = "UNDETERMINED";
        } else if (unitProfit.signum() <= 0) {
            inverseState = "NO_SOLUTION";
        } else {
            minimum = referenceProfitLine.divide(unitProfit, 0, RoundingMode.CEILING).max(BigDecimal.ZERO);
            inverseState = "COMPUTED";
        }
        Boolean gate = null;
        boolean anyNecessary = scenarios.stream().anyMatch(Scenario::necessary);
        if (anyNecessary && referenceProfitLine != null) {
            boolean allPass = true;
            boolean determined = true;
            for (ScenarioResult result : results) {
                Scenario scenario = scenarios.stream().filter(s -> s.code().equals(result.code())).findFirst().orElseThrow();
                if (!scenario.necessary()) {
                    continue;
                }
                if (!"COMPUTED".equals(result.state())) {
                    determined = false;
                } else if (result.contributionProfit().compareTo(referenceProfitLine) < 0) {
                    allPass = false;
                }
            }
            gate = determined ? allPass : null;
        }
        return new Simulation(results, minimum, inverseState, gate);
    }

    private static ScenarioResult scenarioResult(Inputs inputs, Scenario scenario) {
        List<String> missing = new ArrayList<>();
        if (scenario.quantity() == null) {
            missing.add("QUANTITY");
        }
        if (inputs.unitCost() == null) {
            missing.add("UNIT_COST");
        }
        if (!inputs.feesKnown()) {
            missing.add("FEE_SCHEDULE");
        }
        if (!missing.isEmpty()) {
            return new ScenarioResult(scenario.code(), "UNDETERMINED", scenario.quantity(), null, null, missing);
        }
        BigDecimal netPrice = netPrice(inputs);
        BigDecimal revenue = netPrice.multiply(scenario.quantity()).setScale(SCALE, RoundingMode.HALF_EVEN);
        BigDecimal profit = unitProfit(inputs).multiply(scenario.quantity()).setScale(SCALE, RoundingMode.HALF_EVEN);
        return new ScenarioResult(scenario.code(), "COMPUTED", scenario.quantity(), revenue, profit, List.of());
    }

    /** The net price after the seller discount, applied once. */
    static BigDecimal netPrice(Inputs inputs) {
        if (inputs.sellerDiscountRate() == null || inputs.discountAlreadyInNetRevenue()) {
            return inputs.listPrice();
        }
        return inputs.listPrice().multiply(BigDecimal.ONE.subtract(inputs.sellerDiscountRate()))
                .setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    /** Fee per unit at the net price: the highest step whose floor the price reaches. */
    static BigDecimal stepFee(Inputs inputs, BigDecimal netPrice) {
        BigDecimal fee = null;
        for (FeeStep step : inputs.stepFees()) {
            if (netPrice.compareTo(step.priceFloor()) >= 0 && (fee == null || step.feePerUnit().compareTo(fee) > 0)) {
                fee = step.feePerUnit();
            }
        }
        return fee;
    }

    static BigDecimal unitProfit(Inputs inputs) {
        if (inputs.unitCost() == null || !inputs.feesKnown()) {
            return null;
        }
        BigDecimal netPrice = netPrice(inputs);
        // A complete schedule that reaches no step, or an empty complete
        // schedule, is a declared zero fee; only an unknown schedule is unknown.
        BigDecimal fee = stepFee(inputs, netPrice);
        if (fee == null) {
            fee = BigDecimal.ZERO;
        }
        return netPrice.subtract(fee).subtract(inputs.unitCost()).setScale(SCALE, RoundingMode.HALF_EVEN);
    }
}

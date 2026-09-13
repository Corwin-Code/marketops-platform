package com.mimococo.marketops.listingconversion.internal.domain;

import com.mimococo.marketops.analyticsdecision.ContributionProfitCalculation;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.Money;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Finite conditional calculations, never demand forecasts or admission authority.
 * Price tiers select the greatest applicable floor. Within that price tier, the
 * declared per-unit amounts and one fixed commitment apply to the whole scenario.
 * Unknown or unsupported fee terms must remain absent, not be flattened to zero.
 */
public final class PromotionSimulator {
    public static final String MODEL_VERSION = "LC_CONDITIONAL_PROFIT_2";
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999999999");

    private PromotionSimulator() { }

    /** Fee amounts use the explicitly stated input currency. Zero needs an explicit tier. */
    public record FeeStep(BigDecimal priceFloor, BigDecimal feePerUnit) {
        public FeeStep {
            requireNonNegative(priceFloor, false);
            requireNonNegative(feePerUnit, false);
        }
    }

    /** Additional canonical expense families; a null component means unknown. */
    public record Expenses(Money fixedPromotionFee, Money returnLossPerUnit,
                           Money advertisingPerUnit, Money variableTaxPerUnit) {
        public Expenses {
            for (Money value : new Money[] { fixedPromotionFee, returnLossPerUnit, advertisingPerUnit, variableTaxPerUnit }) {
                if (value != null) requireNonNegative(value.amount(), false);
            }
        }
    }

    /**
     * Declared assumptions only. Neither feesKnown nor any scenario flag qualifies a source.
     * A null discount is unknown; explicit zero means no additional seller discount.
     * When already net, the supplied revenue is used without deducting that discount again.
     */
    public record Inputs(BigDecimal listPrice, BigDecimal sellerDiscountRate, boolean discountAlreadyInNetRevenue,
                         BigDecimal unitCost, List<FeeStep> stepFees, boolean feesKnown,
                         String currencyCode, Expenses expenses) {
        public Inputs {
            requireNonNegative(listPrice, false);
            requireNonNegative(unitCost, true);
            requireNonNegative(sellerDiscountRate, true);
            if (sellerDiscountRate != null && sellerDiscountRate.compareTo(BigDecimal.ONE) > 0) invalid();
            stepFees = List.copyOf(stepFees == null ? List.of() : stepFees);
            if (stepFees.size() > 128) invalid();
            var floors = new HashSet<BigDecimal>();
            for (FeeStep step : stepFees) {
                if (!floors.add(step.priceFloor().stripTrailingZeros())) invalid();
            }
            if (currencyCode != null) {
                Money.zero(currencyCode); // Validate currency, not a substituted input fact.
                if (expenses != null) {
                    for (Money amount : new Money[] { expenses.fixedPromotionFee(), expenses.returnLossPerUnit(),
                            expenses.advertisingPerUnit(), expenses.variableTaxPerUnit() }) {
                        if (amount != null && !currencyCode.equals(amount.currencyCode())) {
                            throw OperationRejectedException.of(ErrorCode.CURRENCY_MISMATCH);
                        }
                    }
                }
            }
        }
    }

    public record Scenario(String code, BigDecimal quantity, boolean necessary, boolean conservative) {
        public Scenario {
            if (code == null || code.isBlank() || code.length() > 64) invalid();
            MetadataFieldPolicy.requireText("simulationScenarioCode", code);
            requireNonNegative(quantity, true);
            if (quantity != null && (quantity.stripTrailingZeros().scale() > 0 || quantity.compareTo(MAX_QUANTITY) > 0)) invalid();
        }
    }

    public record ScenarioResult(String code, String state, BigDecimal quantity, BigDecimal netRevenue,
                                 BigDecimal contributionProfit, List<String> missingInputs) {
        public ScenarioResult { missingInputs = List.copyOf(missingInputs); }
    }

    /** conditionalScenariosPassed is only an arithmetic comparison, never a qualified demand gate. */
    public record Simulation(List<ScenarioResult> scenarios, BigDecimal inverseMinimumQuantity,
                             String inverseState, Boolean conditionalScenariosPassed) {
        public Simulation { scenarios = List.copyOf(scenarios); }
    }

    public static Simulation simulate(Inputs inputs, List<Scenario> scenarios, BigDecimal referenceProfitLine) {
        if (inputs == null || scenarios == null || scenarios.size() > 64) invalid();
        if (referenceProfitLine != null && (referenceProfitLine.precision() > 38
                || Math.abs((long) referenceProfitLine.scale()) > 18)) invalid();
        var codes = new HashSet<String>();
        List<ScenarioResult> results = new ArrayList<>();
        boolean necessary = false;
        boolean unknown = false;
        boolean failed = false;
        for (Scenario scenario : scenarios) {
            if (scenario == null || !codes.add(scenario.code())) invalid();
            var result = scenarioResult(inputs, scenario);
            results.add(result);
            if (scenario.necessary()) {
                necessary = true;
                if (!scenario.conservative() || !"COMPUTED".equals(result.state()) || referenceProfitLine == null) unknown = true;
                if ("COMPUTED".equals(result.state()) && referenceProfitLine != null
                        && result.contributionProfit().compareTo(Money.of(referenceProfitLine, inputs.currencyCode()).amount()) < 0) failed = true;
            }
        }
        BigDecimal minimum = null;
        String inverseState = "UNDETERMINED";
        if (missingInputs(inputs).isEmpty() && referenceProfitLine != null) {
            Money target = Money.of(referenceProfitLine, inputs.currencyCode());
            Money atZero = profit(inputs, BigDecimal.ZERO);
            if (atZero.compareTo(target) >= 0) {
                minimum = BigDecimal.ZERO;
                inverseState = "COMPUTED";
            } else {
                BigDecimal slope = profit(inputs, BigDecimal.ONE).minus(atZero).amount();
                if (slope.signum() <= 0) {
                    inverseState = "NO_SOLUTION";
                } else {
                    BigDecimal needed = target.minus(atZero).amount().divide(slope, 0, RoundingMode.CEILING);
                    // No search loop, optimizer or invented support beyond the finite storage domain.
                    if (needed.compareTo(MAX_QUANTITY) <= 0) {
                        minimum = needed;
                        inverseState = "COMPUTED";
                    } else {
                        // Inputs are determined, but no supported quantity meets the target.
                        inverseState = "NO_SOLUTION";
                    }
                }
            }
        }
        Boolean comparison = failed ? Boolean.FALSE : necessary && !unknown ? Boolean.TRUE : null;
        return new Simulation(results, minimum, inverseState, comparison);
    }

    private static ScenarioResult scenarioResult(Inputs inputs, Scenario scenario) {
        List<String> missing = new ArrayList<>();
        if (scenario.quantity() == null) missing.add("QUANTITY");
        missing.addAll(missingInputs(inputs));
        if (!missing.isEmpty()) return new ScenarioResult(scenario.code(), "UNDETERMINED", scenario.quantity(), null, null, missing);
        return new ScenarioResult(scenario.code(), "COMPUTED", scenario.quantity(),
                money(inputs, netPrice(inputs)).times(scenario.quantity()).amount(),
                profit(inputs, scenario.quantity()).amount(), List.of());
    }

    private static List<String> missingInputs(Inputs inputs) {
        var missing = new ArrayList<String>();
        if (inputs.currencyCode() == null) missing.add("CURRENCY");
        if (!inputs.discountAlreadyInNetRevenue() && inputs.sellerDiscountRate() == null) missing.add("SELLER_DISCOUNT");
        if (inputs.unitCost() == null) missing.add("UNIT_COST");
        if (!inputs.feesKnown() || netPrice(inputs) == null || stepFee(inputs, netPrice(inputs)) == null) missing.add("FEE_SCHEDULE");
        Expenses expenses = inputs.expenses();
        if (expenses == null || expenses.fixedPromotionFee() == null) missing.add("FIXED_PROMOTION_FEE");
        if (expenses == null || expenses.returnLossPerUnit() == null) missing.add("RETURN_LOSS");
        if (expenses == null || expenses.advertisingPerUnit() == null) missing.add("ADVERTISING");
        if (expenses == null || expenses.variableTaxPerUnit() == null) missing.add("VARIABLE_TAX");
        return missing;
    }

    public static BigDecimal netPrice(Inputs inputs) {
        if (inputs.discountAlreadyInNetRevenue()) return inputs.listPrice().setScale(Money.SCALE, RoundingMode.HALF_UP);
        if (inputs.sellerDiscountRate() == null) return null;
        return inputs.listPrice().multiply(BigDecimal.ONE.subtract(inputs.sellerDiscountRate()))
                .setScale(Money.SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal stepFee(Inputs inputs, BigDecimal price) {
        FeeStep selected = null;
        for (FeeStep step : inputs.stepFees()) {
            if (price.compareTo(step.priceFloor()) >= 0 && (selected == null || step.priceFloor().compareTo(selected.priceFloor()) > 0)) selected = step;
        }
        return selected == null ? null : selected.feePerUnit();
    }

    private static Money profit(Inputs inputs, BigDecimal quantity) {
        var expenses = inputs.expenses();
        return ContributionProfitCalculation.calculate(money(inputs, netPrice(inputs)).times(quantity),
                money(inputs, inputs.unitCost()).times(quantity),
                money(inputs, stepFee(inputs, netPrice(inputs))).times(quantity).plus(expenses.fixedPromotionFee()),
                expenses.returnLossPerUnit().times(quantity), expenses.advertisingPerUnit().times(quantity),
                expenses.variableTaxPerUnit().times(quantity));
    }

    private static Money money(Inputs inputs, BigDecimal value) { return Money.of(value, inputs.currencyCode()); }

    private static void requireNonNegative(BigDecimal value, boolean nullable) {
        if (value == null) { if (!nullable) invalid(); return; }
        if (value.signum() < 0 || value.precision() > 38 || Math.abs((long) value.scale()) > 18) invalid();
    }

    private static void invalid() { throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED); }
}

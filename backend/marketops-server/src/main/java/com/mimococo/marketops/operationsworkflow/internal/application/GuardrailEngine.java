package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import com.mimococo.marketops.operationsworkflow.internal.domain.GuardrailInput;
import com.mimococo.marketops.operationsworkflow.internal.domain.GuardrailOutcome;
import com.mimococo.marketops.operationsworkflow.internal.domain.PolicyLimits;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The deterministic decision about whether a price may change.
 *
 * <p>This is a pure function of its input. It reads no clock, no database and
 * no configuration, so the same facts produce the same verdict on any day in
 * any environment, and a refusal can be re-derived exactly when somebody
 * disputes it a month later.
 *
 * <p>It collects every blocking reason rather than returning at the first.
 * Refusing one condition at a time turns a single unfixable situation into a
 * week of attempts, and it hides from the operator that a proposal is not
 * nearly ready.
 *
 * <p>Absence never becomes a permissive default. A missing limit, a missing
 * metric and a metric whose confidence is too low each block, because the
 * alternative — treating unknown as acceptable — is exactly how an automated
 * system sells below cost.
 */
final class GuardrailEngine {

    /** Limit codes a price write requires the policy to configure. */
    private static final List<String> REQUIRED_LIMITS = List.of(
            "MIN_DATA_COMPLETENESS", "MAX_INPUT_AGE_SECONDS", "MIN_CONTRIBUTION_MARGIN",
            "MIN_UNIT_CONTRIBUTION_PROFIT", "MAX_SINGLE_CHANGE_RATE",
            "MAX_DAILY_CHANGE_RATE", "COOLDOWN_SECONDS", "MIN_AVAILABLE_UNITS");

    /**
     * Metrics the profit case cannot be made without.
     *
     * <p>Each one is required for a different reason: without the observed price
     * there is nothing to change from, without unit cost and fees there is no
     * profit to project, and without completeness there is no way to know how
     * much of the picture is missing.
     */
    private static final Set<MetricCode> REQUIRED_METRICS = EnumSet.of(
            MetricCode.OBSERVED_SELLING_PRICE, MetricCode.UNIT_COST,
            MetricCode.PLATFORM_FEES, MetricCode.MINIMUM_PRICE,
            MetricCode.DATA_COMPLETENESS);

    /** Scale intermediate rates carry before comparison. */
    private static final int RATE_SCALE = 6;

    private GuardrailEngine() {
    }

    /**
     * Decide, and project what the change would do.
     *
     * <p>The projection is computed even when the verdict blocks, so an operator
     * looking at a refused proposal can still see how far from acceptable it is.
     */
    static GuardrailOutcome evaluate(GuardrailInput input) {
        List<GuardrailReason> reasons = new ArrayList<>();
        Map<String, String> detail = new LinkedHashMap<>();

        appliesToProposal(input, reasons);
        listingIsResolved(input, reasons);

        PolicyLimits policy = input.policy();
        if (policy == null) {
            reasons.add(GuardrailReason.NO_POLICY_IN_FORCE);
        } else {
            detail.put("policyVersion", Integer.toString(policy.policyVersion()));
            detail.put("lifecycleObjective", policy.lifecycleObjective());
            REQUIRED_LIMITS.stream()
                    .filter(limitCode -> !policy.configures(limitCode))
                    .findFirst()
                    .ifPresent(missing -> {
                        detail.put("missingLimit", missing);
                        reasons.add(GuardrailReason.POLICY_LIMIT_NOT_CONFIGURED);
                    });
        }

        BigDecimal currentPrice = metricsAreUsable(input, reasons, detail);
        BigDecimal proposedPrice = input.proposedPrice();
        BigDecimal changeRate = changeRate(currentPrice, proposedPrice);
        if (changeRate != null) {
            detail.put("changeRate", changeRate.toPlainString());
        }

        BigDecimal breakEven = numeric(input, MetricCode.MINIMUM_PRICE);
        BigDecimal unitCost = numeric(input, MetricCode.UNIT_COST);
        BigDecimal fees = numeric(input, MetricCode.PLATFORM_FEES);
        BigDecimal currentUnitProfit = unitProfit(currentPrice, unitCost, fees);
        BigDecimal projectedUnitProfit = unitProfit(proposedPrice, unitCost, fees);
        BigDecimal currentMargin = margin(currentUnitProfit, currentPrice);
        BigDecimal projectedMargin = margin(projectedUnitProfit, proposedPrice);

        if (breakEven != null && proposedPrice.compareTo(breakEven) < 0) {
            detail.put("breakEvenPrice", breakEven.toPlainString());
            reasons.add(GuardrailReason.BELOW_BREAK_EVEN);
        }

        if (policy != null) {
            completenessAndFreshness(input, policy, reasons, detail);
            profitFloors(policy, projectedUnitProfit, projectedMargin, reasons, detail);
            changeSize(policy, input, changeRate, reasons, detail);
            cooldown(policy, input, reasons, detail);
            inventory(policy, input, reasons, detail);
        }

        authorizationBound(input, changeRate, reasons, detail);

        return new GuardrailOutcome(List.copyOf(reasons), Map.copyOf(detail), changeRate,
                breakEven, currentUnitProfit, projectedUnitProfit, currentMargin,
                projectedMargin);
    }

    /** The proposal itself must still be the one that was reviewed. */
    private static void appliesToProposal(GuardrailInput input,
                                          List<GuardrailReason> reasons) {
        if (!input.recommendationValid()) {
            reasons.add(GuardrailReason.RECOMMENDATION_EXPIRED);
        }
        if (!input.entityVersionMatches()) {
            reasons.add(GuardrailReason.ENTITY_VERSION_CHANGED);
        }
    }

    /**
     * The listing must resolve to one internal variant.
     *
     * <p>Cost, and therefore profit, is attached to the internal variant. An
     * unresolved or disputed mapping means the profit case belongs to a
     * different product than the price does.
     */
    private static void listingIsResolved(GuardrailInput input,
                                          List<GuardrailReason> reasons) {
        if (!input.mappingResolved()) {
            reasons.add(GuardrailReason.MAPPING_UNRESOLVED);
        }
        if (input.mappingConflictOpen()) {
            reasons.add(GuardrailReason.MAPPING_CONFLICT_OPEN);
        }
        if (input.diagnosisBlocksExecution()) {
            reasons.add(GuardrailReason.DIAGNOSIS_BLOCKS_EXECUTION);
        }
    }

    /**
     * Every metric the case needs must be present and confident enough.
     *
     * @return the observed selling price, or {@code null} when it is unusable
     */
    private static BigDecimal metricsAreUsable(GuardrailInput input,
                                               List<GuardrailReason> reasons,
                                               Map<String, String> detail) {
        List<String> unavailable = new ArrayList<>();
        List<String> lowConfidence = new ArrayList<>();
        for (MetricCode required : REQUIRED_METRICS) {
            MetricValueView value = input.metrics().get(required);
            if (value == null || !value.available() || value.numericValue() == null) {
                unavailable.add(required.name());
                continue;
            }
            if (!value.confidenceState().sufficientForWrite()) {
                lowConfidence.add(required.name() + '=' + value.confidenceState().name());
            }
        }
        if (!unavailable.isEmpty()) {
            detail.put("unavailableMetrics", String.join(",", unavailable));
            reasons.add(GuardrailReason.REQUIRED_METRIC_UNAVAILABLE);
        }
        if (!lowConfidence.isEmpty()) {
            detail.put("lowConfidenceMetrics", String.join(",", lowConfidence));
            reasons.add(GuardrailReason.METRIC_CONFIDENCE_INSUFFICIENT);
        }
        BigDecimal observed = numeric(input, MetricCode.OBSERVED_SELLING_PRICE);
        if (observed != null) {
            detail.put("currentPrice", observed.toPlainString());
        }
        detail.put("proposedPrice", input.proposedPrice().toPlainString());
        return input.currentPrice() != null ? input.currentPrice() : observed;
    }

    /** How much of the picture is present, and how old the freshest part is. */
    private static void completenessAndFreshness(GuardrailInput input,
                                                 PolicyLimits policy,
                                                 List<GuardrailReason> reasons,
                                                 Map<String, String> detail) {
        BigDecimal completeness = numeric(input, MetricCode.DATA_COMPLETENESS);
        Optional<BigDecimal> minimum = policy.rate("MIN_DATA_COMPLETENESS");
        if (completeness != null && minimum.isPresent()) {
            detail.put("dataCompleteness", completeness.toPlainString());
            if (completeness.compareTo(minimum.get()) < 0) {
                reasons.add(GuardrailReason.DATA_COMPLETENESS_BELOW_MINIMUM);
            }
        }

        Optional<Long> maximumAge = policy.duration("MAX_INPUT_AGE_SECONDS");
        if (maximumAge.isEmpty()) {
            return;
        }
        Long oldest = input.metrics().values().stream()
                .map(MetricValueView::freshnessSeconds)
                .filter(java.util.Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
        if (oldest == null) {
            return;
        }
        detail.put("inputAgeSeconds", Long.toString(oldest));
        if (oldest > maximumAge.get()) {
            reasons.add(GuardrailReason.INPUT_TOO_STALE);
        }
    }

    /** The proposal must leave the unit above the policy's profit floors. */
    private static void profitFloors(PolicyLimits policy,
                                     BigDecimal projectedUnitProfit,
                                     BigDecimal projectedMargin,
                                     List<GuardrailReason> reasons,
                                     Map<String, String> detail) {
        Optional<BigDecimal> minimumProfit = policy.amount("MIN_UNIT_CONTRIBUTION_PROFIT");
        if (projectedUnitProfit != null && minimumProfit.isPresent()) {
            detail.put("projectedUnitProfit", projectedUnitProfit.toPlainString());
            if (projectedUnitProfit.compareTo(minimumProfit.get()) < 0) {
                reasons.add(GuardrailReason.UNIT_PROFIT_BELOW_MINIMUM);
            }
        }
        Optional<BigDecimal> minimumMargin = policy.rate("MIN_CONTRIBUTION_MARGIN");
        if (projectedMargin != null && minimumMargin.isPresent()) {
            detail.put("projectedMargin", projectedMargin.toPlainString());
            if (projectedMargin.compareTo(minimumMargin.get()) < 0) {
                reasons.add(GuardrailReason.MARGIN_BELOW_MINIMUM);
            }
        }
    }

    /**
     * Neither this change nor the day's total may exceed what the policy allows.
     *
     * <p>Both bounds read the magnitude of the change, not its direction. A
     * large cut is as disruptive to a marketplace listing as a large rise, and
     * the daily bound exists precisely so a sequence of individually acceptable
     * steps cannot add up to one nobody approved.
     */
    private static void changeSize(PolicyLimits policy,
                                   GuardrailInput input,
                                   BigDecimal changeRate,
                                   List<GuardrailReason> reasons,
                                   Map<String, String> detail) {
        if (changeRate == null) {
            return;
        }
        BigDecimal magnitude = changeRate.abs();
        policy.rate("MAX_SINGLE_CHANGE_RATE").ifPresent(maximum -> {
            if (magnitude.compareTo(maximum) > 0) {
                reasons.add(GuardrailReason.SINGLE_CHANGE_TOO_LARGE);
            }
        });
        policy.rate("MAX_DAILY_CHANGE_RATE").ifPresent(maximum -> {
            BigDecimal cumulative = input.cumulativeDailyChangeRate().abs().add(magnitude);
            detail.put("cumulativeDailyChangeRate", cumulative.toPlainString());
            if (cumulative.compareTo(maximum) > 0) {
                reasons.add(GuardrailReason.DAILY_CHANGE_EXCEEDED);
            }
        });
    }

    /** A price that changed recently must be left alone long enough to observe. */
    private static void cooldown(PolicyLimits policy,
                                 GuardrailInput input,
                                 List<GuardrailReason> reasons,
                                 Map<String, String> detail) {
        Optional<Long> cooldownSeconds = policy.duration("COOLDOWN_SECONDS");
        if (cooldownSeconds.isEmpty() || input.lastChangeAt() == null) {
            return;
        }
        long elapsed = Duration.between(input.lastChangeAt(), input.evaluatedAt()).toSeconds();
        detail.put("secondsSinceLastChange", Long.toString(elapsed));
        if (elapsed < cooldownSeconds.get()) {
            reasons.add(GuardrailReason.COOLDOWN_ACTIVE);
        }
    }

    /**
     * There must be stock worth changing the price of.
     *
     * <p>Platform availability is read rather than internal stock, because the
     * price applies to what the marketplace can actually sell.
     */
    private static void inventory(PolicyLimits policy,
                                  GuardrailInput input,
                                  List<GuardrailReason> reasons,
                                  Map<String, String> detail) {
        Optional<Integer> minimumUnits = policy.count("MIN_AVAILABLE_UNITS");
        BigDecimal available = numeric(input, MetricCode.PLATFORM_AVAILABLE_UNITS);
        if (minimumUnits.isEmpty() || available == null) {
            return;
        }
        detail.put("availableUnits", available.toPlainString());
        if (available.compareTo(BigDecimal.valueOf(minimumUnits.get())) < 0) {
            reasons.add(GuardrailReason.INVENTORY_BELOW_MINIMUM);
        }
    }

    /**
     * A standing authorization bounds the change it can be spent on.
     *
     * <p>This is checked here as well as inside the consuming function. The
     * function is the guarantee; this check is what lets an operator see the
     * refusal before spending a use rather than after.
     */
    private static void authorizationBound(GuardrailInput input,
                                           BigDecimal changeRate,
                                           List<GuardrailReason> reasons,
                                           Map<String, String> detail) {
        BigDecimal bound = input.authorizationMaxChangeRate();
        if (bound == null) {
            return;
        }
        detail.put("authorizationMaxChangeRate", bound.toPlainString());
        if (changeRate == null || changeRate.abs().compareTo(bound) > 0) {
            reasons.add(GuardrailReason.CHANGE_EXCEEDS_POLICY_AUTHORIZATION);
        }
    }

    private static BigDecimal numeric(GuardrailInput input, MetricCode code) {
        MetricValueView value = input.metrics().get(code);
        return value == null || !value.available() ? null : value.numericValue();
    }

    /** The proportional change from the current price, signed. */
    private static BigDecimal changeRate(BigDecimal currentPrice, BigDecimal proposedPrice) {
        if (currentPrice == null || currentPrice.signum() == 0) {
            return null;
        }
        return proposedPrice.subtract(currentPrice)
                .divide(currentPrice, RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** What one unit contributes at a price, after cost and platform fees. */
    private static BigDecimal unitProfit(BigDecimal price, BigDecimal unitCost,
                                         BigDecimal fees) {
        if (price == null || unitCost == null || fees == null) {
            return null;
        }
        return price.subtract(unitCost).subtract(fees);
    }

    /** What that contribution is as a proportion of the price. */
    private static BigDecimal margin(BigDecimal unitProfit, BigDecimal price) {
        if (unitProfit == null || price == null || price.signum() == 0) {
            return null;
        }
        return unitProfit.divide(price, RATE_SCALE, RoundingMode.HALF_UP);
    }
}

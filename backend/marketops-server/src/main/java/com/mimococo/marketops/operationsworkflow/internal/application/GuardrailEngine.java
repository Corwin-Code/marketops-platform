package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.analyticsdecision.DecisionFreshness;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsCalculator;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsProfile;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsResolution;
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
import java.util.UUID;
import com.mimococo.marketops.shared.Money;

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
            MetricCode.REQUIRED_PROFIT_PER_UNIT, MetricCode.SAFETY_BUFFER_PER_UNIT,
            MetricCode.DATA_COMPLETENESS, MetricCode.PLATFORM_AVAILABLE_UNITS);

    /** Money-valued assertions that must all use the policy currency. */
    private static final Set<MetricCode> MONETARY_METRICS = EnumSet.of(
            MetricCode.OBSERVED_SELLING_PRICE, MetricCode.UNIT_COST,
            MetricCode.REQUIRED_PROFIT_PER_UNIT, MetricCode.SAFETY_BUFFER_PER_UNIT);

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

        ProjectedDecision projected = project(input, currentPrice, proposedPrice,
                reasons, detail);
        BigDecimal minimumPrice = projected.minimumPrice();
        BigDecimal breakEven = projected.breakEvenPrice();
        BigDecimal currentUnitProfit = projected.currentUnitProfit();
        BigDecimal projectedUnitProfit = projected.projectedUnitProfit();
        BigDecimal currentMargin = margin(currentUnitProfit, currentPrice);
        BigDecimal projectedMargin = margin(projectedUnitProfit, proposedPrice);

        if (breakEven != null && proposedPrice.compareTo(breakEven) < 0) {
            detail.put("breakEvenPrice", breakEven.toPlainString());
            reasons.add(GuardrailReason.BELOW_BREAK_EVEN);
        }
        if (minimumPrice != null && proposedPrice.compareTo(minimumPrice) < 0) {
            detail.put("minimumPrice", minimumPrice.toPlainString());
            reasons.add(GuardrailReason.BELOW_MINIMUM_PRICE);
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
                breakEven, minimumPrice, currentUnitProfit, projectedUnitProfit,
                currentMargin, projectedMargin, projected.profileId(),
                projected.profileVersion(), input.fulfillmentModeCode(),
                projected.componentIds());
    }

    /** Resolve and evaluate one exact profile for current and proposed prices. */
    private static ProjectedDecision project(GuardrailInput input,
                                             BigDecimal currentPrice,
                                             BigDecimal proposedPrice,
                                             List<GuardrailReason> reasons,
                                             Map<String, String> detail) {
        PriceEconomicsResolution resolution = input.economics();
        if (!resolution.available()) {
            GuardrailReason reason = switch (resolution.status()) {
                case AMBIGUOUS -> GuardrailReason.ECONOMICS_PROFILE_AMBIGUOUS;
                case EXPIRED -> GuardrailReason.ECONOMICS_PROFILE_EXPIRED;
                case UNVERIFIED -> GuardrailReason.ECONOMICS_PROFILE_UNVERIFIED;
                case MISSING -> GuardrailReason.ECONOMICS_PROFILE_MISSING;
                case UNSUPPORTED, AVAILABLE ->
                        GuardrailReason.PROJECTED_ECONOMICS_UNAVAILABLE;
            };
            reasons.add(reason);
            detail.put("economicsResolution", resolution.status() + ":" + resolution.detail());
            return ProjectedDecision.unavailable();
        }

        PriceEconomicsProfile profile = resolution.profile();
        detail.put("economicsProfileId", profile.profileId().toString());
        detail.put("economicsProfileVersion", Integer.toString(profile.profileVersion()));
        detail.put("economicsVerificationState", profile.verificationState().name());
        detail.put("fulfillmentModeCode", profile.fulfillmentModeCode());

        PriceEconomicsCalculator.Projection current = currentPrice == null
                ? null : PriceEconomicsCalculator.project(profile, currentPrice);
        PriceEconomicsCalculator.Projection proposed =
                PriceEconomicsCalculator.project(profile, proposedPrice);
        BigDecimal unitCost = numeric(input, MetricCode.UNIT_COST);
        BigDecimal requiredProfit = numeric(input, MetricCode.REQUIRED_PROFIT_PER_UNIT);
        BigDecimal safetyBuffer = numeric(input, MetricCode.SAFETY_BUFFER_PER_UNIT);
        PriceEconomicsCalculator.Solution solution = null;
        if (unitCost != null && requiredProfit != null && safetyBuffer != null) {
            solution = PriceEconomicsCalculator.solve(profile,
                    Money.of(unitCost, profile.currencyCode()),
                    Money.of(requiredProfit, profile.currencyCode()),
                    Money.of(safetyBuffer, profile.currencyCode()));
        }
        if (current == null || !current.available() || !proposed.available()
                || solution == null || !solution.available()) {
            reasons.add(GuardrailReason.PROJECTED_ECONOMICS_UNAVAILABLE);
            List<String> projectionReasons = new ArrayList<>();
            if (current == null) {
                projectionReasons.add("CURRENT_PRICE_UNAVAILABLE");
            } else {
                projectionReasons.addAll(current.reasons());
            }
            projectionReasons.addAll(proposed.reasons());
            if (solution == null) {
                projectionReasons.add("SOLVER_INPUT_UNAVAILABLE");
            } else {
                projectionReasons.addAll(solution.reasons());
            }
            detail.put("projectionBlockingReasons", String.join(",", projectionReasons));
            return new ProjectedDecision(profile.profileId(), profile.profileVersion(),
                    solution == null ? null : solution.breakEvenPrice(),
                    solution == null ? null : solution.minimumPrice(), null, null,
                    proposed.componentIds());
        }

        BigDecimal currentProfit = unitProfit(currentPrice, unitCost,
                current.totalVariableCost());
        BigDecimal proposedProfit = unitProfit(proposedPrice, unitCost,
                proposed.totalVariableCost());
        detail.put("currentProjectedVariableCost",
                current.totalVariableCost().toPlainString());
        detail.put("proposedProjectedVariableCost",
                proposed.totalVariableCost().toPlainString());
        detail.put("projectedComponentIds", proposed.componentIds().stream()
                .map(UUID::toString).sorted().reduce("", (left, right) ->
                        left.isEmpty() ? right : left + ',' + right));
        detail.put("projectedFamilyCoverage", proposed.familyCoverage().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + ',' + right));
        return new ProjectedDecision(profile.profileId(), profile.profileVersion(),
                solution.breakEvenPrice(), solution.minimumPrice(), currentProfit,
                proposedProfit, proposed.componentIds());
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
        currencyConsistency(input, reasons, detail);
        BigDecimal observed = numeric(input, MetricCode.OBSERVED_SELLING_PRICE);
        if (observed != null) {
            detail.put("currentPrice", observed.toPlainString());
        }
        detail.put("proposedPrice", input.proposedPrice().toPlainString());
        return input.currentPrice() != null ? input.currentPrice() : observed;
    }

    /** How much is present, and how old each independent source feed is now. */
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
        DecisionFreshness freshness = input.decisionFreshness();
        List<String> freshnessMissing = freshness.missingFeeds().stream()
                .map(Enum::name).sorted().toList();
        if (!freshnessMissing.isEmpty()) {
            detail.put("freshnessUnavailableFeeds", String.join(",", freshnessMissing));
            reasons.add(GuardrailReason.INPUT_FRESHNESS_UNAVAILABLE);
            return;
        }
        Map<DecisionFreshness.Feed, Long> ages = freshness.agesAt(input.evaluatedAt());
        if (ages.size() != freshness.requiredFeeds().size()
                || ages.values().stream().anyMatch(age -> age < 0)) {
            reasons.add(GuardrailReason.INPUT_FRESHNESS_UNAVAILABLE);
            return;
        }
        long oldest = ages.values().stream().max(Long::compareTo).orElseThrow();
        detail.put("inputAgeSeconds", Long.toString(oldest));
        detail.put("freshnessWatermarks", freshness.requiredFeeds().stream().map(feed -> {
            DecisionFreshness.Watermark watermark = freshness.watermarks().get(feed);
            return feed + "=" + watermark.watermarkId() + '@' + watermark.effectiveAt()
                    + ":" + ages.get(feed);
        }).sorted().reduce("", (left, right) ->
                left.isEmpty() ? right : left + ',' + right));
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
        if (available == null) {
            reasons.add(GuardrailReason.INVENTORY_EVIDENCE_UNAVAILABLE);
            return;
        }
        if (minimumUnits.isEmpty()) {
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
    private static BigDecimal unitProfit(BigDecimal price, BigDecimal... costs) {
        if (price == null || java.util.Arrays.stream(costs)
                .anyMatch(java.util.Objects::isNull)) {
            return null;
        }
        return java.util.Arrays.stream(costs).reduce(price, BigDecimal::subtract);
    }

    /** Money is comparable only when every required assertion matches policy. */
    private static void currencyConsistency(GuardrailInput input,
                                            List<GuardrailReason> reasons,
                                            Map<String, String> detail) {
        String expected = input.policy() == null
                ? input.currentPriceCurrency() : input.policy().currencyCode();
        List<String> mismatches = new ArrayList<>();
        if (expected == null || input.currentPriceCurrency() == null
                || !expected.equals(input.currentPriceCurrency())) {
            mismatches.add("CURRENT_PRICE=" + input.currentPriceCurrency());
        }
        if (input.economics().available()
                && !java.util.Objects.equals(expected,
                        input.economics().profile().currencyCode())) {
            mismatches.add("ECONOMICS_PROFILE="
                    + input.economics().profile().currencyCode());
        }
        for (MetricCode code : MONETARY_METRICS) {
            MetricValueView value = input.metrics().get(code);
            if (value != null && value.available()
                    && !java.util.Objects.equals(expected, value.currencyCode())) {
                mismatches.add(code + "=" + value.currencyCode());
            }
        }
        if (!mismatches.isEmpty()) {
            detail.put("expectedCurrency", String.valueOf(expected));
            detail.put("currencyMismatches", String.join(",", mismatches));
            reasons.add(GuardrailReason.CURRENCY_MISMATCH);
        }
    }

    /** What that contribution is as a proportion of the price. */
    private static BigDecimal margin(BigDecimal unitProfit, BigDecimal price) {
        if (unitProfit == null || price == null || price.signum() == 0) {
            return null;
        }
        return unitProfit.divide(price, RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Projection values kept together so no result can be paired with another profile. */
    private record ProjectedDecision(
            UUID profileId,
            Integer profileVersion,
            BigDecimal breakEvenPrice,
            BigDecimal minimumPrice,
            BigDecimal currentUnitProfit,
            BigDecimal projectedUnitProfit,
            List<UUID> componentIds) {

        private ProjectedDecision {
            componentIds = List.copyOf(componentIds);
        }

        static ProjectedDecision unavailable() {
            return new ProjectedDecision(null, null, null, null, null, null, List.of());
        }
    }
}

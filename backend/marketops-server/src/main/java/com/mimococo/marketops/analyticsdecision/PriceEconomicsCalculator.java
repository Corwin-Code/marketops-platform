package com.mimococo.marketops.analyticsdecision;

import com.mimococo.marketops.operatingfacts.FeeTotals;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Deterministic projection and bounded Minimum Price solver shared by every consumer. */
public final class PriceEconomicsCalculator {

    private static final int INTERNAL_SCALE = 12;
    private static final BigDecimal MONEY_STEP = new BigDecimal("0.0001");
    private static final int BISECTION_STEPS = 128;

    private PriceEconomicsCalculator() {
    }

    /** Project every required family at one exact price. */
    public static Projection project(PriceEconomicsProfile profile, BigDecimal price) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(price, "price");

        List<String> reasons = new ArrayList<>();
        Map<FeeFamily, FeeCoverageState> coverage = new EnumMap<>(FeeFamily.class);
        List<ProjectedComponent> projected = new ArrayList<>();

        if (!profile.verificationState().usableForEngineeringDecision()) {
            reasons.add("PROFILE_UNVERIFIED");
        }
        if (price.signum() <= 0
                || price.compareTo(profile.minimumSupportedPrice()) < 0
                || price.compareTo(profile.maximumSupportedPrice()) > 0) {
            reasons.add("PRICE_OUT_OF_SUPPORTED_RANGE");
        }

        for (FeeFamily family : FeeFamily.values()) {
            PriceEconomicsProfile.Applicability applicability =
                    profile.familyApplicability().get(family);
            List<PriceEconomicsProfile.Component> familyComponents = profile.components().stream()
                    .filter(component -> component.family() == family).toList();
            if (applicability == null) {
                coverage.put(family, FeeCoverageState.MISSING_OR_INCOMPLETE);
                reasons.add("FAMILY_CONTRACT_MISSING:" + family);
                continue;
            }
            if (applicability
                    == PriceEconomicsProfile.Applicability.VERIFIED_NOT_APPLICABLE) {
                coverage.put(family, FeeCoverageState.VERIFIED_NOT_APPLICABLE);
                if (!familyComponents.isEmpty()) {
                    reasons.add("INAPPLICABLE_FAMILY_HAS_COMPONENTS:" + family);
                }
                continue;
            }

            Set<String> componentCodes = new LinkedHashSet<>();
            familyComponents.forEach(component -> componentCodes.add(component.componentCode()));
            if (componentCodes.isEmpty()) {
                coverage.put(family, FeeCoverageState.MISSING_OR_INCOMPLETE);
                reasons.add("REQUIRED_FAMILY_HAS_NO_COMPONENT:" + family);
                continue;
            }

            BigDecimal familyTotal = BigDecimal.ZERO;
            boolean complete = true;
            for (String componentCode : componentCodes) {
                List<PriceEconomicsProfile.Component> applicable = familyComponents.stream()
                        .filter(component -> component.componentCode().equals(componentCode))
                        .filter(component -> component.appliesAt(price)).toList();
                if (applicable.size() != 1) {
                    complete = false;
                    reasons.add((applicable.isEmpty() ? "COMPONENT_TIER_MISSING:"
                            : "COMPONENT_TIER_AMBIGUOUS:") + family + ':' + componentCode);
                    continue;
                }
                PriceEconomicsProfile.Component component = applicable.getFirst();
                BigDecimal amount = amount(component, price);
                if (amount == null || amount.signum() < 0) {
                    complete = false;
                    reasons.add("COMPONENT_SHAPE_UNSUPPORTED:" + family + ':' + componentCode);
                    continue;
                }
                familyTotal = familyTotal.add(amount);
                projected.add(new ProjectedComponent(component.componentId(), componentCode,
                        family, amount.setScale(Money.SCALE, RoundingMode.HALF_UP)));
            }
            coverage.put(family, complete
                    ? (familyTotal.signum() == 0
                            ? FeeCoverageState.PRESENT_EXPLICIT_ZERO
                            : FeeCoverageState.PRESENT_NONZERO)
                    : FeeCoverageState.MISSING_OR_INCOMPLETE);
        }

        boolean available = reasons.isEmpty()
                && coverage.values().stream()
                        .noneMatch(state -> state == FeeCoverageState.MISSING_OR_INCOMPLETE);
        BigDecimal total = available
                ? projected.stream().map(ProjectedComponent::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(Money.SCALE, RoundingMode.HALF_UP)
                : null;
        return new Projection(profile.profileId(), profile.profileVersion(), price,
                profile.currencyCode(), total, coverage, projected, reasons);
    }

    /**
     * Solve break-even and contractual Minimum Price over the profile's bounded domain.
     *
     * <p>The same component projection used for the proposed price is evaluated
     * during the solver. A profile whose net-price function decreases at a tier
     * boundary is rejected as unsupported rather than selecting one of several
     * ambiguous roots.
     */
    public static Solution solve(PriceEconomicsProfile profile,
                                 Money unitCost,
                                 Money requiredProfit,
                                 Money safetyBuffer) {
        List<String> reasons = new ArrayList<>();
        if (profile == null || unitCost == null || requiredProfit == null
                || safetyBuffer == null) {
            return Solution.unavailable("REQUIRED_SOLVER_INPUT_MISSING");
        }
        if (!profile.currencyCode().equals(unitCost.currencyCode())
                || !profile.currencyCode().equals(requiredProfit.currencyCode())
                || !profile.currencyCode().equals(safetyBuffer.currencyCode())) {
            return Solution.unavailable("SOLVER_CURRENCY_CONFLICT");
        }
        if (!monotone(profile)) {
            return Solution.unavailable("NON_MONOTONE_PROFILE");
        }

        BigDecimal breakEven = solveThreshold(profile, unitCost.amount(), reasons);
        BigDecimal requiredBase = unitCost.amount().add(requiredProfit.amount())
                .add(safetyBuffer.amount());
        BigDecimal minimum = solveThreshold(profile, requiredBase, reasons);
        if (!reasons.isEmpty() || breakEven == null || minimum == null) {
            return new Solution(null, null, List.copyOf(reasons));
        }
        return new Solution(breakEven, minimum, List.of());
    }

    /** Check exact historical fee-family coverage before using the aggregate. */
    public static HistoricalCoverage historicalCoverage(PriceEconomicsProfile profile,
                                                         FeeTotals fees) {
        Map<FeeFamily, FeeCoverageState> states = new EnumMap<>(FeeFamily.class);
        List<String> reasons = new ArrayList<>();
        if (profile == null || fees == null || !fees.evidence().usable()
                || fees.evidence().currencyConflict()) {
            return new HistoricalCoverage(false, states,
                    List.of("HISTORICAL_FEE_EVIDENCE_UNAVAILABLE"));
        }
        if (fees.byCategory().containsKey("UNKNOWN")) {
            reasons.add("UNKNOWN_FEE_CATEGORY");
        }
        for (FeeFamily family : FeeFamily.values()) {
            if (!family.historicalPlatformFeeFamily()) {
                continue;
            }
            PriceEconomicsProfile.Applicability applicability =
                    profile.familyApplicability().get(family);
            if (applicability
                    == PriceEconomicsProfile.Applicability.VERIFIED_NOT_APPLICABLE) {
                states.put(family, FeeCoverageState.VERIFIED_NOT_APPLICABLE);
                continue;
            }
            if (applicability != PriceEconomicsProfile.Applicability.REQUIRED) {
                states.put(family, FeeCoverageState.MISSING_OR_INCOMPLETE);
                reasons.add("FAMILY_CONTRACT_MISSING:" + family);
                continue;
            }
            List<Money> amounts = family.historicalCategories().stream()
                    .map(fees.byCategory()::get).filter(Objects::nonNull).toList();
            if (amounts.isEmpty()) {
                states.put(family, FeeCoverageState.MISSING_OR_INCOMPLETE);
                reasons.add("HISTORICAL_FAMILY_MISSING:" + family);
                continue;
            }
            String expectedCurrency = amounts.getFirst().currencyCode();
            if (amounts.stream().anyMatch(amount ->
                    !expectedCurrency.equals(amount.currencyCode()))) {
                states.put(family, FeeCoverageState.MISSING_OR_INCOMPLETE);
                reasons.add("HISTORICAL_FAMILY_CURRENCY_CONFLICT:" + family);
                continue;
            }
            BigDecimal total = amounts.stream().map(Money::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            states.put(family, total.signum() == 0
                    ? FeeCoverageState.PRESENT_EXPLICIT_ZERO
                    : FeeCoverageState.PRESENT_NONZERO);
        }
        boolean complete = reasons.isEmpty() && states.values().stream()
                .noneMatch(state -> state == FeeCoverageState.MISSING_OR_INCOMPLETE);
        return new HistoricalCoverage(complete, states, reasons);
    }

    private static BigDecimal amount(PriceEconomicsProfile.Component component,
                                     BigDecimal price) {
        BigDecimal fixed = component.fixedAmount();
        BigDecimal rate = component.rate();
        return switch (component.kind()) {
            case FIXED -> fixed != null && rate == null ? fixed : null;
            case PERCENTAGE -> fixed == null && rate != null
                    ? price.multiply(rate) : null;
            case FIXED_PLUS_PERCENTAGE -> fixed != null && rate != null
                    ? fixed.add(price.multiply(rate)) : null;
        };
    }

    private static boolean monotone(PriceEconomicsProfile profile) {
        TreeSet<BigDecimal> boundaries = new TreeSet<>();
        boundaries.add(profile.minimumSupportedPrice());
        boundaries.add(profile.maximumSupportedPrice());
        profile.components().forEach(component -> {
            if (component.lowerPriceInclusive() != null
                    && component.lowerPriceInclusive().compareTo(
                            profile.minimumSupportedPrice()) > 0
                    && component.lowerPriceInclusive().compareTo(
                            profile.maximumSupportedPrice()) < 0) {
                boundaries.add(component.lowerPriceInclusive());
            }
            if (component.upperPriceExclusive() != null
                    && component.upperPriceExclusive().compareTo(
                            profile.minimumSupportedPrice()) > 0
                    && component.upperPriceExclusive().compareTo(
                            profile.maximumSupportedPrice()) < 0) {
                boundaries.add(component.upperPriceExclusive());
            }
        });
        List<BigDecimal> points = List.copyOf(boundaries);
        for (int index = 0; index < points.size(); index++) {
            BigDecimal point = points.get(index);
            Projection right = project(profile, point);
            if (!right.available()) {
                return false;
            }
            if (index > 0) {
                BigDecimal previousPoint = points.get(index - 1);
                BigDecimal before = point.subtract(MONEY_STEP).max(previousPoint);
                Projection left = project(profile, before);
                if (!left.available()
                        || net(point, right.totalVariableCost(), BigDecimal.ZERO)
                                .compareTo(net(before, left.totalVariableCost(),
                                        BigDecimal.ZERO)) < 0) {
                    return false;
                }
                if (before.compareTo(previousPoint) > 0) {
                    Projection start = project(profile, previousPoint);
                    if (!start.available()
                            || net(before, left.totalVariableCost(), BigDecimal.ZERO)
                                    .compareTo(net(previousPoint,
                                            start.totalVariableCost(), BigDecimal.ZERO)) < 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static BigDecimal solveThreshold(PriceEconomicsProfile profile,
                                             BigDecimal fixedBase,
                                             List<String> reasons) {
        BigDecimal low = profile.minimumSupportedPrice();
        BigDecimal high = profile.maximumSupportedPrice();
        Projection lowProjection = project(profile, low);
        Projection highProjection = project(profile, high);
        if (!lowProjection.available() || !highProjection.available()) {
            reasons.add("PROJECTION_UNAVAILABLE_DURING_SOLVE");
            return null;
        }
        if (net(low, lowProjection.totalVariableCost(), fixedBase).signum() >= 0) {
            return low.setScale(Money.SCALE, RoundingMode.CEILING);
        }
        if (net(high, highProjection.totalVariableCost(), fixedBase).signum() < 0) {
            reasons.add("NO_SOLUTION_IN_SUPPORTED_RANGE");
            return null;
        }
        for (int step = 0; step < BISECTION_STEPS; step++) {
            BigDecimal middle = low.add(high)
                    .divide(BigDecimal.valueOf(2), INTERNAL_SCALE, RoundingMode.HALF_UP);
            Projection projection = project(profile, middle);
            if (!projection.available()) {
                reasons.add("PROJECTION_UNAVAILABLE_DURING_SOLVE");
                return null;
            }
            if (net(middle, projection.totalVariableCost(), fixedBase).signum() >= 0) {
                high = middle;
            } else {
                low = middle;
            }
        }
        BigDecimal candidate = high.setScale(Money.SCALE, RoundingMode.CEILING);
        Projection projection = project(profile, candidate);
        if (!projection.available()
                || net(candidate, projection.totalVariableCost(), fixedBase).signum() < 0
                || candidate.compareTo(profile.maximumSupportedPrice()) > 0) {
            reasons.add("NO_SOLUTION_IN_SUPPORTED_RANGE");
            return null;
        }
        return candidate;
    }

    private static BigDecimal net(BigDecimal price,
                                  BigDecimal variableCost,
                                  BigDecimal fixedBase) {
        return price.subtract(variableCost).subtract(fixedBase);
    }

    /** Projection of every component at one price. */
    public record Projection(
            UUID profileId,
            int profileVersion,
            BigDecimal price,
            String currencyCode,
            BigDecimal totalVariableCost,
            Map<FeeFamily, FeeCoverageState> familyCoverage,
            List<ProjectedComponent> components,
            List<String> reasons) {

        public Projection {
            familyCoverage = Map.copyOf(familyCoverage);
            components = List.copyOf(components);
            reasons = List.copyOf(reasons);
        }

        public boolean available() {
            return totalVariableCost != null && reasons.isEmpty();
        }

        /** Stable identities bound into Guardrail and command authority. */
        public List<UUID> componentIds() {
            return components.stream().map(ProjectedComponent::componentId).sorted().toList();
        }
    }

    /** One selected component tier and its projected monetary amount. */
    public record ProjectedComponent(
            UUID componentId,
            String componentCode,
            FeeFamily family,
            BigDecimal amount) {
    }

    /** Bounded price-solution result. */
    public record Solution(
            BigDecimal breakEvenPrice,
            BigDecimal minimumPrice,
            List<String> reasons) {

        public Solution {
            reasons = List.copyOf(reasons);
        }

        public boolean available() {
            return breakEvenPrice != null && minimumPrice != null && reasons.isEmpty();
        }

        public static Solution unavailable(String reason) {
            return new Solution(null, null, List.of(reason));
        }
    }

    /** Historical family coverage kept distinct from the numeric aggregate. */
    public record HistoricalCoverage(
            boolean complete,
            Map<FeeFamily, FeeCoverageState> familyStates,
            List<String> reasons) {

        public HistoricalCoverage {
            familyStates = Map.copyOf(familyStates);
            reasons = List.copyOf(reasons);
        }
    }
}

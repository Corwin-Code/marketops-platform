package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.analyticsdecision.internal.config.AnalyticsProperties;
import com.mimococo.marketops.analyticsdecision.internal.domain.ComputedMetric;
import com.mimococo.marketops.analyticsdecision.internal.domain.RuleOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The ordered deterministic rules, evaluated against canonical metrics.
 *
 * <p>Order carries meaning. DATA_BLOCKED runs first because a subject whose
 * inputs are missing, stale or conflicting cannot be diagnosed at all; when it
 * triggers, every later rule records DECLINED rather than a verdict. A system
 * that answered "conversion is low" from incomplete data would be confidently
 * wrong at the moment somebody is deciding a price.
 *
 * <p>Declining is a first-class outcome and is always recorded. An operator has
 * to be able to see that a rule could not answer and why; silence would read as
 * a clean result and would hide exactly the coverage gaps worth fixing.
 *
 * <p>Every triggered finding carries the comparison it made — the observed value
 * and the threshold — so a rule can be checked without rerunning it, and a
 * threshold somebody disputes can be argued about with the number in front of
 * them.
 */
@Service
public class DiagnosisEngine {

    /** Rule identifiers, matching the seeded rule set exactly. */
    private static final String DATA_BLOCKED = "DATA_BLOCKED";
    private static final String NEGATIVE_MARGIN = "NEGATIVE_MARGIN";
    private static final String STOCKOUT_RISK = "STOCKOUT_RISK";
    private static final String HIGH_RETURN = "HIGH_RETURN";
    private static final String LOW_IMPRESSION = "LOW_IMPRESSION";
    private static final String LOW_CLICK_THROUGH = "LOW_CLICK_THROUGH";
    private static final String LOW_CONVERSION = "LOW_CONVERSION";
    private static final String ADVERTISING_INEFFICIENT = "ADVERTISING_INEFFICIENT";
    private static final String PRICE_BELOW_MINIMUM = "PRICE_BELOW_MINIMUM";
    private static final String LISTING_NOT_SELLABLE = "LISTING_NOT_SELLABLE";
    private static final String DEMAND_NOT_CONVERTING = "DEMAND_NOT_CONVERTING";
    private static final String PRICE_GAP_REDUCIBLE = "PRICE_GAP_REDUCIBLE";
    private static final String PRICE_GAP_PARTIAL = "PRICE_GAP_PARTIAL";
    private static final String PRICE_GAP_STRUCTURAL = "PRICE_GAP_STRUCTURAL";
    private static final String LOW_SEARCH_EXPOSURE = "LOW_SEARCH_EXPOSURE";
    private static final String CONTENT_BELOW_TARGET = "CONTENT_BELOW_TARGET";

    /** The version of every rule this release evaluates. */
    public static final int RULE_VERSION = 1;

    /** Why a rule could not answer. */
    private static final String BLOCKED_BY_EARLIER_RULE = "BLOCKED_BY_EARLIER_RULE";
    private static final String REQUIRED_METRIC_UNAVAILABLE = "REQUIRED_METRIC_UNAVAILABLE";
    private static final String REQUIRED_METRIC_UNDEFINED = "REQUIRED_METRIC_UNDEFINED";
    private static final String MAPPING_UNRESOLVED = "MAPPING_UNRESOLVED";
    private static final String THRESHOLD_NOT_CONFIGURED = "THRESHOLD_NOT_CONFIGURED";
    private static final String INSUFFICIENT_SAMPLE = "INSUFFICIENT_SAMPLE";

    private final AnalyticsProperties properties;

    DiagnosisEngine(AnalyticsProperties properties) {
        this.properties = properties;
    }

    /**
     * Evaluate every rule in order against one subject's computed metrics.
     *
     * @return the outcomes in rule order, one per rule, none omitted
     */
    public List<RuleOutcome> evaluate(Map<MetricCode, ComputedMetric> metrics) {
        List<RuleOutcome> outcomes = new ArrayList<>();
        RuleOutcome blocked = evaluateDataBlocked(metrics);
        outcomes.add(blocked);

        boolean blocking = blocked.outcome() == DiagnosisFindingView.Outcome.TRIGGERED;
        outcomes.add(guarded(blocking, () -> evaluateNegativeMargin(metrics), NEGATIVE_MARGIN));
        outcomes.add(guarded(blocking, () -> evaluateStockoutRisk(metrics), STOCKOUT_RISK));
        outcomes.add(guarded(blocking, () -> evaluateHighReturn(metrics), HIGH_RETURN));
        outcomes.add(guarded(blocking, () -> evaluateLowImpression(metrics), LOW_IMPRESSION));
        outcomes.add(guarded(blocking, () -> evaluateLowClickThrough(metrics),
                LOW_CLICK_THROUGH));
        outcomes.add(guarded(blocking, () -> evaluateLowConversion(metrics), LOW_CONVERSION));
        outcomes.add(guarded(blocking, () -> evaluateAdvertising(metrics),
                ADVERTISING_INEFFICIENT));
        outcomes.add(guarded(blocking, () -> evaluatePriceBelowMinimum(metrics),
                PRICE_BELOW_MINIMUM));

        // Listing rules describe a listing nobody buys, so they cannot wait for realized
        // profit data: a block on that data does not decline them.
        outcomes.add(evaluateNotSellable(metrics));
        outcomes.add(evaluateDemandNotConverting(metrics));
        outcomes.addAll(evaluatePriceGap(metrics));
        outcomes.add(evaluateLowSearchExposure(metrics));
        outcomes.add(evaluateContentBelowTarget(metrics));
        return List.copyOf(outcomes);
    }

    // -----------------------------------------------------------------------
    // Listing rules (version 1, independent of realized profit)
    // -----------------------------------------------------------------------

    private RuleOutcome evaluateNotSellable(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric sellable = metrics.get(MetricCode.LISTING_SELLABLE);
        Optional<RuleOutcome> unavailable = requireAvailable(LISTING_NOT_SELLABLE, sellable);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        Map<String, String> detail = detail("listingSellable", sellable.numericValue().toPlainString());
        return sellable.numericValue().signum() == 0
                ? RuleOutcome.triggered(LISTING_NOT_SELLABLE, DiagnosisFindingView.Severity.CRITICAL,
                        detail, List.of(sellable))
                : RuleOutcome.clear(LISTING_NOT_SELLABLE, detail, List.of(sellable));
    }

    /**
     * Buyers look for an in-stock listing and nobody orders it: the core
     * question the store diagnosis answers. The other listing rules say why.
     */
    private RuleOutcome evaluateDemandNotConverting(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric search = metrics.get(MetricCode.SEARCH_USERS);
        ComputedMetric ordered = metrics.get(MetricCode.ORDERED_UNITS);
        ComputedMetric available = metrics.get(MetricCode.PLATFORM_AVAILABLE_UNITS);
        Optional<RuleOutcome> unavailable = requireAvailable(DEMAND_NOT_CONVERTING, search, ordered, available);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        Long floor = properties.getThresholds().getDemandSearchUsersFloor();
        if (floor == null) {
            return RuleOutcome.declined(DEMAND_NOT_CONVERTING, THRESHOLD_NOT_CONFIGURED, Map.of());
        }
        Map<String, String> detail = detail(
                "searchUsers", search.numericValue().toPlainString(),
                "orderedUnits", ordered.numericValue().toPlainString(),
                "platformAvailableUnits", available.numericValue().toPlainString(),
                "demandSearchUsersFloor", Long.toString(floor));
        List<ComputedMetric> read = List.of(search, ordered, available);
        boolean demand = search.numericValue().compareTo(BigDecimal.valueOf(floor)) >= 0;
        return demand && available.numericValue().signum() > 0 && ordered.numericValue().signum() == 0
                ? RuleOutcome.triggered(DEMAND_NOT_CONVERTING, DiagnosisFindingView.Severity.WARNING, detail, read)
                : RuleOutcome.clear(DEMAND_NOT_CONVERTING, detail, read);
    }

    /**
     * Whether a buyer price above the lowest competitor price can be closed:
     * profitably at the minimum margin (reducible), only below it (partial), or
     * not without a loss (structural). Exactly one triggers when there is a gap.
     * An undefined break-even or target price means no price reaches it, so it
     * counts as above any competitor price. The competitor price is grade C
     * platform analytics: this diagnoses, it never authorizes a price change.
     */
    private List<RuleOutcome> evaluatePriceGap(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric price = metrics.get(MetricCode.OBSERVED_SELLING_PRICE);
        ComputedMetric competitor = metrics.get(MetricCode.PLATFORM_COMPETITOR_MIN_PRICE);
        ComputedMetric breakEven = metrics.get(MetricCode.PROJECTED_BREAK_EVEN_PRICE);
        ComputedMetric target = metrics.get(MetricCode.TARGET_MARGIN_PRICE);
        BigDecimal marginRate = properties.getThresholds().getMinimumUnitMarginRate();
        Optional<RuleOutcome> reducibleMissing = requireAvailable(PRICE_GAP_REDUCIBLE, price, competitor)
                .or(() -> requirePresent(PRICE_GAP_REDUCIBLE, target));
        Optional<RuleOutcome> partialMissing = requireAvailable(PRICE_GAP_PARTIAL, price, competitor)
                .or(() -> requirePresent(PRICE_GAP_PARTIAL, breakEven, target));
        Optional<RuleOutcome> structuralMissing = requireAvailable(PRICE_GAP_STRUCTURAL, price, competitor)
                .or(() -> requirePresent(PRICE_GAP_STRUCTURAL, breakEven));
        if (marginRate == null) {
            reducibleMissing = Optional.of(RuleOutcome.declined(PRICE_GAP_REDUCIBLE, THRESHOLD_NOT_CONFIGURED,
                    Map.of()));
            partialMissing = Optional.of(RuleOutcome.declined(PRICE_GAP_PARTIAL, THRESHOLD_NOT_CONFIGURED,
                    Map.of()));
        }
        Map<String, String> detail = new LinkedHashMap<>();
        if (price != null && price.valueState() == ValueState.AVAILABLE) {
            detail.put("buyerPrice", price.numericValue().toPlainString());
            detail.put("currencyCode", String.valueOf(price.currencyCode()));
        }
        if (competitor != null && competitor.valueState() == ValueState.AVAILABLE) {
            detail.put("platformCompetitorMinPrice", competitor.numericValue().toPlainString());
        }
        if (breakEven != null && breakEven.valueState() == ValueState.AVAILABLE) {
            detail.put("breakEvenPrice", breakEven.numericValue().toPlainString());
        }
        if (target != null && target.valueState() == ValueState.AVAILABLE) {
            detail.put("targetMarginPrice", target.numericValue().toPlainString());
        }
        if (marginRate != null) {
            detail.put("minimumUnitMarginRate", marginRate.toPlainString());
        }
        boolean comparable = price != null && competitor != null
                && price.valueState() == ValueState.AVAILABLE && competitor.valueState() == ValueState.AVAILABLE
                && java.util.Objects.equals(price.currencyCode(), competitor.currencyCode());
        boolean gap = comparable && price.numericValue().compareTo(competitor.numericValue()) > 0;
        if (comparable && competitor.numericValue().signum() > 0) {
            detail.put("premiumOverCompetitor", price.numericValue()
                    .divide(competitor.numericValue(), 4, java.math.RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).toPlainString());
        }
        Map<String, String> shared = Map.copyOf(detail);
        List<RuleOutcome> outcomes = new ArrayList<>();
        outcomes.add(reducibleMissing.orElseGet(() -> {
            boolean reducible = gap && atMost(target, competitor.numericValue());
            return reducible
                    ? RuleOutcome.triggered(PRICE_GAP_REDUCIBLE, DiagnosisFindingView.Severity.WARNING, shared,
                            List.of(price, competitor, target))
                    : RuleOutcome.clear(PRICE_GAP_REDUCIBLE, shared, List.of(price, competitor, target));
        }));
        outcomes.add(partialMissing.orElseGet(() -> {
            boolean partial = gap && atMost(breakEven, competitor.numericValue())
                    && !atMost(target, competitor.numericValue());
            return partial
                    ? RuleOutcome.triggered(PRICE_GAP_PARTIAL, DiagnosisFindingView.Severity.WARNING, shared,
                            List.of(price, competitor, breakEven, target))
                    : RuleOutcome.clear(PRICE_GAP_PARTIAL, shared, List.of(price, competitor, breakEven, target));
        }));
        outcomes.add(structuralMissing.orElseGet(() -> {
            boolean structural = gap && !atMost(breakEven, competitor.numericValue());
            return structural
                    ? RuleOutcome.triggered(PRICE_GAP_STRUCTURAL, DiagnosisFindingView.Severity.WARNING, shared,
                            List.of(price, competitor, breakEven))
                    : RuleOutcome.clear(PRICE_GAP_STRUCTURAL, shared, List.of(price, competitor, breakEven));
        }));
        return outcomes;
    }

    /** An in-stock, sellable listing that almost nobody finds in search. */
    private RuleOutcome evaluateLowSearchExposure(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric search = metrics.get(MetricCode.SEARCH_USERS);
        ComputedMetric available = metrics.get(MetricCode.PLATFORM_AVAILABLE_UNITS);
        Optional<RuleOutcome> unavailable = requireAvailable(LOW_SEARCH_EXPOSURE, search, available);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        Long ceiling = properties.getThresholds().getLowExposureSearchUsers();
        if (ceiling == null) {
            return RuleOutcome.declined(LOW_SEARCH_EXPOSURE, THRESHOLD_NOT_CONFIGURED, Map.of());
        }
        ComputedMetric sellable = metrics.get(MetricCode.LISTING_SELLABLE);
        boolean notSellable = sellable != null && sellable.valueState() == ValueState.AVAILABLE
                && sellable.numericValue().signum() == 0;
        Map<String, String> detail = detail(
                "searchUsers", search.numericValue().toPlainString(),
                "platformAvailableUnits", available.numericValue().toPlainString(),
                "lowExposureSearchUsers", Long.toString(ceiling));
        List<ComputedMetric> read = List.of(search, available);
        boolean low = search.numericValue().compareTo(BigDecimal.valueOf(ceiling)) < 0;
        return low && available.numericValue().signum() > 0 && !notSellable
                ? RuleOutcome.triggered(LOW_SEARCH_EXPOSURE, DiagnosisFindingView.Severity.WARNING, detail, read)
                : RuleOutcome.clear(LOW_SEARCH_EXPOSURE, detail, read);
    }

    private RuleOutcome evaluateContentBelowTarget(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric rating = metrics.get(MetricCode.CONTENT_RATING);
        Optional<RuleOutcome> unavailable = requireAvailable(CONTENT_BELOW_TARGET, rating);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        BigDecimal floor = properties.getThresholds().getContentRatingFloor();
        if (floor == null) {
            return RuleOutcome.declined(CONTENT_BELOW_TARGET, THRESHOLD_NOT_CONFIGURED, Map.of());
        }
        Map<String, String> detail = detail(
                "contentRating", rating.numericValue().toPlainString(),
                "contentRatingFloor", floor.toPlainString());
        return rating.numericValue().compareTo(floor) < 0
                ? RuleOutcome.triggered(CONTENT_BELOW_TARGET, DiagnosisFindingView.Severity.WARNING, detail,
                        List.of(rating))
                : RuleOutcome.clear(CONTENT_BELOW_TARGET, detail, List.of(rating));
    }

    /**
     * Whether the subject can be diagnosed at all.
     *
     * <p>Three conditions block, and they are checked in the order an operator
     * would fix them: an unresolved mapping first, because nothing downstream is
     * attributable without it; then missing profit inputs; then staleness or a
     * conflict in what did arrive.
     */
    private RuleOutcome evaluateDataBlocked(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric completeness = metrics.get(MetricCode.DATA_COMPLETENESS);
        BigDecimal floor = properties.getThresholds().getMinimumDataCompleteness();
        if (floor == null) {
            return RuleOutcome.declined(DATA_BLOCKED, THRESHOLD_NOT_CONFIGURED, Map.of());
        }
        if (completeness == null || completeness.valueState() != ValueState.AVAILABLE) {
            return RuleOutcome.declined(DATA_BLOCKED, REQUIRED_METRIC_UNAVAILABLE, Map.of());
        }
        if (completeness.confidenceState() == ConfidenceState.INCOMPLETE) {
            return RuleOutcome.triggered(DATA_BLOCKED, DiagnosisFindingView.Severity.CRITICAL,
                    detail("reason", MAPPING_UNRESOLVED,
                            "dataCompleteness", completeness.numericValue().toPlainString(),
                            "threshold", floor.toPlainString()),
                    List.of(completeness));
        }

        List<String> conflicted = metrics.values().stream()
                .filter(metric -> metric.confidenceState() == ConfidenceState.CONFLICTED)
                .map(metric -> metric.metricCode().name())
                .sorted()
                .toList();
        List<String> stale = metrics.values().stream()
                .filter(metric -> metric.confidenceState() == ConfidenceState.STALE)
                .map(metric -> metric.metricCode().name())
                .sorted()
                .toList();
        boolean belowFloor = completeness.numericValue().compareTo(floor) < 0;
        if (belowFloor || !conflicted.isEmpty() || !stale.isEmpty()) {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("dataCompleteness", completeness.numericValue().toPlainString());
            detail.put("threshold", floor.toPlainString());
            detail.put("conflictedMetrics", String.join(",", conflicted));
            detail.put("staleMetrics", String.join(",", stale));
            return RuleOutcome.triggered(DATA_BLOCKED, DiagnosisFindingView.Severity.CRITICAL,
                    detail, List.of(completeness));
        }
        return RuleOutcome.clear(DATA_BLOCKED,
                detail("dataCompleteness", completeness.numericValue().toPlainString(),
                        "threshold", floor.toPlainString()),
                List.of(completeness));
    }

    private RuleOutcome evaluateNegativeMargin(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric profit = metrics.get(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT);
        Optional<RuleOutcome> unavailable = requireAvailable(NEGATIVE_MARGIN, profit);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        Map<String, String> detail = detail(
                "operationalContributionProfit", profit.numericValue().toPlainString(),
                "currencyCode", String.valueOf(profit.currencyCode()));
        return profit.numericValue().signum() <= 0
                ? RuleOutcome.triggered(NEGATIVE_MARGIN, DiagnosisFindingView.Severity.CRITICAL,
                        detail, List.of(profit))
                : RuleOutcome.clear(NEGATIVE_MARGIN, detail, List.of(profit));
    }

    /**
     * Whether the listing is about to stop selling for want of stock.
     *
     * <p>Zero available units triggers on its own. Otherwise the days of cover
     * are compared with the safety horizon; a subject that sold nothing has
     * undefined cover, which is declined rather than treated as infinite
     * safety — a listing with no stock and no sales is exactly the one worth
     * looking at.
     */
    private RuleOutcome evaluateStockoutRisk(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric available = metrics.get(MetricCode.PLATFORM_AVAILABLE_UNITS);
        Optional<RuleOutcome> unavailable = requireAvailable(STOCKOUT_RISK, available);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        ComputedMetric cover = metrics.get(MetricCode.STOCK_COVER_DAYS);
        ComputedMetric internal = metrics.get(MetricCode.INTERNAL_AVAILABLE_UNITS);
        BigDecimal horizon = properties.getThresholds().getStockCoverDaysFloor();
        if (horizon == null) {
            return RuleOutcome.declined(STOCKOUT_RISK, THRESHOLD_NOT_CONFIGURED, Map.of());
        }

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("platformAvailableUnits", available.numericValue().toPlainString());
        detail.put("stockCoverDaysFloor", horizon.toPlainString());
        if (internal != null && internal.valueState() == ValueState.AVAILABLE) {
            detail.put("internalAvailableUnits", internal.numericValue().toPlainString());
        }
        List<ComputedMetric> read = new ArrayList<>(List.of(available));
        if (available.numericValue().signum() == 0) {
            detail.put("condition", "NO_PLATFORM_STOCK");
            return RuleOutcome.triggered(STOCKOUT_RISK, DiagnosisFindingView.Severity.CRITICAL,
                    detail, read);
        }
        if (cover == null || cover.valueState() != ValueState.AVAILABLE) {
            detail.put("condition", "COVER_NOT_COMPUTABLE");
            return RuleOutcome.clear(STOCKOUT_RISK, detail, read);
        }
        read.add(cover);
        detail.put("stockCoverDays", cover.numericValue().toPlainString());
        return cover.numericValue().compareTo(horizon) < 0
                ? RuleOutcome.triggered(STOCKOUT_RISK, DiagnosisFindingView.Severity.CRITICAL,
                        detail, read)
                : RuleOutcome.clear(STOCKOUT_RISK, detail, read);
    }

    private RuleOutcome evaluateHighReturn(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric rate = metrics.get(MetricCode.RETURN_RATE);
        ComputedMetric units = metrics.get(MetricCode.COMPLETED_UNITS);
        Optional<RuleOutcome> unavailable = requireAvailable(HIGH_RETURN, rate, units);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        BigDecimal ceiling = properties.getThresholds().getHighReturnRate();
        Long minimumUnits = properties.getThresholds().getHighReturnMinimumUnits();
        if (ceiling == null || minimumUnits == null) {
            return RuleOutcome.declined(HIGH_RETURN, THRESHOLD_NOT_CONFIGURED, Map.of());
        }
        // A ratio over a handful of orders is noise. Declining below the sample
        // floor keeps the queue free of listings that sold three units and had
        // one returned.
        if (units.numericValue().compareTo(BigDecimal.valueOf(minimumUnits)) < 0) {
            return RuleOutcome.declined(HIGH_RETURN, INSUFFICIENT_SAMPLE,
                    detail("completedUnits", units.numericValue().toPlainString(),
                            "minimumUnits", Long.toString(minimumUnits)));
        }
        Map<String, String> detail = detail(
                "returnRate", rate.numericValue().toPlainString(),
                "threshold", ceiling.toPlainString());
        return rate.numericValue().compareTo(ceiling) > 0
                ? RuleOutcome.triggered(HIGH_RETURN, DiagnosisFindingView.Severity.WARNING,
                        detail, List.of(rate, units))
                : RuleOutcome.clear(HIGH_RETURN, detail, List.of(rate, units));
    }

    private RuleOutcome evaluateLowImpression(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric impressions = metrics.get(MetricCode.IMPRESSIONS);
        Optional<RuleOutcome> unavailable = requireAvailable(LOW_IMPRESSION, impressions);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        Long floor = properties.getThresholds().getLowImpressionFloor();
        if (floor == null) {
            return RuleOutcome.declined(LOW_IMPRESSION, THRESHOLD_NOT_CONFIGURED, Map.of());
        }
        Map<String, String> detail = detail(
                "impressions", impressions.numericValue().toPlainString(),
                "threshold", Long.toString(floor));
        return impressions.numericValue().compareTo(BigDecimal.valueOf(floor)) < 0
                ? RuleOutcome.triggered(LOW_IMPRESSION, DiagnosisFindingView.Severity.WARNING,
                        detail, List.of(impressions))
                : RuleOutcome.clear(LOW_IMPRESSION, detail, List.of(impressions));
    }

    private RuleOutcome evaluateLowClickThrough(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric rate = metrics.get(MetricCode.CLICK_THROUGH_RATE);
        ComputedMetric impressions = metrics.get(MetricCode.IMPRESSIONS);
        Optional<RuleOutcome> unavailable = requireAvailable(LOW_CLICK_THROUGH, rate,
                impressions);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        BigDecimal floor = properties.getThresholds().getLowClickThroughRate();
        Long minimumImpressions =
                properties.getThresholds().getLowClickThroughMinimumImpressions();
        if (floor == null || minimumImpressions == null) {
            return RuleOutcome.declined(LOW_CLICK_THROUGH, THRESHOLD_NOT_CONFIGURED, Map.of());
        }
        if (impressions.numericValue().compareTo(BigDecimal.valueOf(minimumImpressions)) < 0) {
            return RuleOutcome.declined(LOW_CLICK_THROUGH, INSUFFICIENT_SAMPLE,
                    detail("impressions", impressions.numericValue().toPlainString(),
                            "minimumImpressions", Long.toString(minimumImpressions)));
        }
        Map<String, String> detail = detail(
                "clickThroughRate", rate.numericValue().toPlainString(),
                "threshold", floor.toPlainString());
        return rate.numericValue().compareTo(floor) < 0
                ? RuleOutcome.triggered(LOW_CLICK_THROUGH, DiagnosisFindingView.Severity.WARNING,
                        detail, List.of(rate, impressions))
                : RuleOutcome.clear(LOW_CLICK_THROUGH, detail, List.of(rate, impressions));
    }

    private RuleOutcome evaluateLowConversion(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric rate = metrics.get(MetricCode.CONVERSION_RATE);
        Optional<RuleOutcome> unavailable = requireAvailable(LOW_CONVERSION, rate);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        BigDecimal floor = properties.getThresholds().getLowConversionRate();
        Long minimumReach = properties.getThresholds().getLowConversionMinimumReach();
        if (floor == null || minimumReach == null) {
            return RuleOutcome.declined(LOW_CONVERSION, THRESHOLD_NOT_CONFIGURED, Map.of());
        }
        ComputedMetric clicks = metrics.get(MetricCode.CLICKS);
        if (clicks != null && clicks.valueState() == ValueState.AVAILABLE
                && clicks.numericValue().compareTo(BigDecimal.valueOf(minimumReach)) < 0) {
            return RuleOutcome.declined(LOW_CONVERSION, INSUFFICIENT_SAMPLE,
                    detail("clicks", clicks.numericValue().toPlainString(),
                            "minimumReach", Long.toString(minimumReach)));
        }
        Map<String, String> detail = detail(
                "conversionRate", rate.numericValue().toPlainString(),
                "threshold", floor.toPlainString());
        return rate.numericValue().compareTo(floor) < 0
                ? RuleOutcome.triggered(LOW_CONVERSION, DiagnosisFindingView.Severity.WARNING,
                        detail, List.of(rate))
                : RuleOutcome.clear(LOW_CONVERSION, detail, List.of(rate));
    }

    private RuleOutcome evaluateAdvertising(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric costOfSale = metrics.get(MetricCode.AD_COST_OF_SALE);
        ComputedMetric spend = metrics.get(MetricCode.AD_SPEND);
        Optional<RuleOutcome> unavailable = requireAvailable(ADVERTISING_INEFFICIENT,
                costOfSale, spend);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        BigDecimal ceiling = properties.getThresholds().getAdvertisingCostOfSaleCeiling();
        if (ceiling == null) {
            return RuleOutcome.declined(ADVERTISING_INEFFICIENT, THRESHOLD_NOT_CONFIGURED,
                    Map.of());
        }
        Map<String, String> detail = detail(
                "advertisingCostOfSale", costOfSale.numericValue().toPlainString(),
                "threshold", ceiling.toPlainString());
        return costOfSale.numericValue().compareTo(ceiling) > 0
                ? RuleOutcome.triggered(ADVERTISING_INEFFICIENT,
                        DiagnosisFindingView.Severity.WARNING, detail,
                        List.of(costOfSale, spend))
                : RuleOutcome.clear(ADVERTISING_INEFFICIENT, detail, List.of(costOfSale, spend));
    }

    private RuleOutcome evaluatePriceBelowMinimum(Map<MetricCode, ComputedMetric> metrics) {
        ComputedMetric breakEven = metrics.get(MetricCode.MINIMUM_PRICE);
        ComputedMetric observed = metrics.get(MetricCode.OBSERVED_SELLING_PRICE);
        Optional<RuleOutcome> unavailable = requireAvailable(PRICE_BELOW_MINIMUM,
                breakEven, observed);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        Map<String, String> detail = detail(
                "observedSellingPrice", observed.numericValue().toPlainString(),
                "breakEvenPrice", breakEven.numericValue().toPlainString());
        return observed.numericValue().compareTo(breakEven.numericValue()) < 0
                ? RuleOutcome.triggered(PRICE_BELOW_MINIMUM,
                        DiagnosisFindingView.Severity.CRITICAL, detail,
                        List.of(observed, breakEven))
                : RuleOutcome.clear(PRICE_BELOW_MINIMUM, detail, List.of(observed, breakEven));
    }

    // -----------------------------------------------------------------------
    // Shared evaluation mechanics
    // -----------------------------------------------------------------------

    private static RuleOutcome guarded(boolean blocked,
                                       java.util.function.Supplier<RuleOutcome> rule,
                                       String ruleCode) {
        return blocked
                ? RuleOutcome.declined(ruleCode, BLOCKED_BY_EARLIER_RULE, Map.of())
                : rule.get();
    }

    /**
     * Decline when any required metric did not produce a usable number.
     *
     * <p>An unavailable input and an undefined one decline for different
     * reasons, because they lead to different fixes: one is a coverage problem
     * and the other is a business situation.
     */
    private static Optional<RuleOutcome> requireAvailable(String ruleCode,
                                                          ComputedMetric... required) {
        for (ComputedMetric metric : required) {
            if (metric == null || metric.valueState() == ValueState.NOT_AVAILABLE) {
                return Optional.of(RuleOutcome.declined(ruleCode, REQUIRED_METRIC_UNAVAILABLE,
                        metric == null ? Map.of()
                                : detail("metric", metric.metricCode().name())));
            }
            if (metric.valueState() == ValueState.UNDEFINED) {
                return Optional.of(RuleOutcome.declined(ruleCode, REQUIRED_METRIC_UNDEFINED,
                        detail("metric", metric.metricCode().name())));
            }
        }
        return Optional.empty();
    }

    /** Like {@link #requireAvailable}, but an undefined value is an answer, not a gap. */
    private static Optional<RuleOutcome> requirePresent(String ruleCode, ComputedMetric... required) {
        for (ComputedMetric metric : required) {
            if (metric == null || metric.valueState() == ValueState.NOT_AVAILABLE) {
                return Optional.of(RuleOutcome.declined(ruleCode, REQUIRED_METRIC_UNAVAILABLE,
                        metric == null ? Map.of()
                                : detail("metric", metric.metricCode().name())));
            }
        }
        return Optional.empty();
    }

    /** Whether a price bound is reached at or below {@code value}; an undefined bound never is. */
    private static boolean atMost(ComputedMetric bound, BigDecimal value) {
        return bound.valueState() == ValueState.AVAILABLE && bound.numericValue().compareTo(value) <= 0;
    }

    private static Map<String, String> detail(String... pairs) {
        Map<String, String> detail = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            detail.put(pairs[index], pairs[index + 1]);
        }
        return Map.copyOf(detail);
    }
}

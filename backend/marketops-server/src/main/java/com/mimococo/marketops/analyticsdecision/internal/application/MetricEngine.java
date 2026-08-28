package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.analyticsdecision.internal.config.AnalyticsProperties;
import com.mimococo.marketops.analyticsdecision.internal.domain.ComputedMetric;
import com.mimococo.marketops.analyticsdecision.internal.domain.MetricInput;
import com.mimococo.marketops.operatingfacts.AdvertisingTotals;
import com.mimococo.marketops.operatingfacts.CostSnapshot;
import com.mimococo.marketops.operatingfacts.FactEvidence;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.FeeTotals;
import com.mimococo.marketops.operatingfacts.FinanceInputSnapshot;
import com.mimococo.marketops.operatingfacts.InternalStockSnapshot;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.ReturnTotals;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.operatingfacts.SalesTotals;
import com.mimococo.marketops.operatingfacts.StockSnapshot;
import com.mimococo.marketops.operatingfacts.TrafficTotals;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single deterministic calculator for canonical metrics.
 *
 * <p>Every value it produces is a pure function of the facts it read. It writes
 * nothing, reads no clock of its own, and cannot be influenced by a model: given
 * the same facts it produces the same numbers, the same confidence states and
 * the same digests, which is what makes a stored value reproducible and a
 * golden-case test meaningful.
 *
 * <p>Absence is preserved end to end. A metric whose inputs no source published
 * is NOT_AVAILABLE; a ratio whose denominator is zero is UNDEFINED; neither is
 * ever a zero. That distinction is the whole reason the product can say "we
 * cannot tell you" instead of quietly reporting a loss of nothing.
 *
 * <p>Confidence follows the weakest input. A profit figure built from one
 * accrued fee is pending settlement however confirmed the rest of it is, and a
 * figure built from an estimated tax rate says so, because the write gate reads
 * this state and not the number.
 */
@Service
public class MetricEngine {

    /** Scale ratios are computed at; four places is finer than any threshold. */
    private static final int RATIO_SCALE = 6;

    /** Scale day counts are computed at. */
    private static final int DAY_SCALE = 2;

    /** The profit inputs data completeness is measured over. */
    private static final int PROFIT_INPUT_COUNT = 6;

    private final OperatingFactQuery facts;
    private final ListingIdentityDirectory listings;
    private final AnalyticsProperties properties;

    MetricEngine(OperatingFactQuery facts,
                 ListingIdentityDirectory listings,
                 AnalyticsProperties properties) {
        this.facts = facts;
        this.listings = listings;
        this.properties = properties;
    }

    /**
     * Compute every metric for one platform listing variant over one window.
     *
     * @param organizationId organization the subject belongs to
     * @param storeId store the subject sits on
     * @param listingVariantId the subject
     * @param window the observation window
     * @param periodEnd first instant after the window
     */
    public Map<MetricCode, ComputedMetric> compute(UUID organizationId,
                                                   UUID storeId,
                                                   UUID listingVariantId,
                                                   MetricWindow window,
                                                   Instant periodEnd) {
        FactWindow factWindow = FactWindow.endingAt(periodEnd, window.length());
        Map<MetricCode, ComputedMetric> metrics = new EnumMap<>(MetricCode.class);

        TrafficTotals traffic = facts.traffic(listingVariantId, factWindow);
        SalesTotals completed = facts.sales(listingVariantId, SaleStage.COMPLETED, null,
                factWindow);
        SalesTotals retained = facts.sales(listingVariantId, SaleStage.RETAINED, window.days(),
                factWindow);
        SalesTotals settled = facts.sales(listingVariantId, SaleStage.SETTLED, null, factWindow);
        ReturnTotals returns = facts.returns(listingVariantId, factWindow);
        FeeTotals fees = facts.fees(listingVariantId, factWindow);
        AdvertisingTotals advertising = facts.advertising(listingVariantId, factWindow);
        StockSnapshot stock = facts.latestStock(listingVariantId, periodEnd);

        Optional<UUID> mappedVariant = listings.internalVariantAt(listingVariantId, periodEnd);
        Optional<CostSnapshot> unitCost = mappedVariant
                .flatMap(variantId -> facts.unitCost(variantId, periodEnd));
        InternalStockSnapshot internalStock = mappedVariant
                .map(variantId -> facts.internalStock(variantId, periodEnd))
                .orElseGet(InternalStockSnapshot::absent);
        Optional<FinanceInputSnapshot> taxRate = facts.financeInput(organizationId,
                "VARIABLE_TAX_RATE", storeId, mappedVariant.orElse(null), periodEnd);

        putCount(metrics, MetricCode.IMPRESSIONS, traffic.impressions(), traffic.evidence());
        putCount(metrics, MetricCode.CLICKS, traffic.clicks(), traffic.evidence());
        metrics.put(MetricCode.CLICK_THROUGH_RATE,
                ratio(MetricCode.CLICK_THROUGH_RATE, traffic.clicks(), traffic.impressions(),
                        traffic.evidence()));
        metrics.put(MetricCode.CONVERSION_RATE,
                ratio(MetricCode.CONVERSION_RATE,
                        completed.available() ? completed.units() : null,
                        traffic.strongestReachMeasure(),
                        merge(traffic.evidence(), completed.evidence())));

        putCount(metrics, MetricCode.COMPLETED_UNITS,
                completed.available() ? completed.units() : null, completed.evidence());
        putMoney(metrics, MetricCode.COMPLETED_NET_SALES, completed.netAmount(),
                completed.evidence());
        putCount(metrics, MetricCode.RETAINED_UNITS,
                retained.available() ? retained.units() : null, retained.evidence());
        putMoney(metrics, MetricCode.RETAINED_NET_SALES, retained.netAmount(),
                retained.evidence());
        putMoney(metrics, MetricCode.SETTLED_NET_SALES, settled.netAmount(), settled.evidence());

        putCount(metrics, MetricCode.RETURN_UNITS,
                returns.available() ? returns.units() : null, returns.evidence());
        metrics.put(MetricCode.RETURN_RATE,
                ratio(MetricCode.RETURN_RATE,
                        returns.available() ? returns.units() : null,
                        completed.available() ? completed.units() : null,
                        merge(returns.evidence(), completed.evidence())));

        putCount(metrics, MetricCode.PLATFORM_AVAILABLE_UNITS,
                stock.evidence().present() ? (long) stock.totalAvailable() : null,
                stock.evidence());
        putCount(metrics, MetricCode.INTERNAL_AVAILABLE_UNITS,
                internalStock.available()
                        ? (long) (internalStock.quantityOnHand()
                                - (internalStock.quantityReserved() == null
                                        ? 0 : internalStock.quantityReserved()))
                        : null,
                internalStock.available()
                        ? FactEvidence.of(List.of(internalStock.provenanceId()),
                                internalStock.observedAt())
                        : FactEvidence.none());
        metrics.put(MetricCode.STOCK_COVER_DAYS, stockCoverDays(stock, completed, window));

        putMoney(metrics, MetricCode.AD_SPEND, advertising.spendAmount(),
                advertising.evidence());
        metrics.put(MetricCode.AD_COST_OF_SALE,
                moneyRatio(MetricCode.AD_COST_OF_SALE, advertising.spendAmount(),
                        completed.netAmount(),
                        merge(advertising.evidence(), completed.evidence())));

        metrics.put(MetricCode.UNIT_COST, unitCost
                .map(cost -> new ComputedMetric(MetricCode.UNIT_COST, ValueState.AVAILABLE,
                        cost.unitCost().amount(), cost.unitCost().currencyCode(),
                        ConfidenceState.CANONICAL_CONFIRMED, cost.effectiveFrom(),
                        List.of(MetricInput.costVersion(cost.costVersionId()),
                                MetricInput.provenance(cost.provenanceId()))))
                .orElseGet(() -> absent(MetricCode.UNIT_COST, ConfidenceState.INCOMPLETE)));

        putMoney(metrics, MetricCode.PLATFORM_FEES, fees.available() ? fees.total() : null,
                fees.evidence());
        putMoney(metrics, MetricCode.RETURN_LOSS,
                returns.available() ? returns.lossAmount() : null, returns.evidence());
        metrics.put(MetricCode.VARIABLE_TAX_ESTIMATE,
                variableTax(completed, taxRate));

        metrics.put(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                contributionProfit(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                        completed, unitCost, fees, returns, advertising,
                        metrics.get(MetricCode.VARIABLE_TAX_ESTIMATE)));
        metrics.put(MetricCode.SETTLED_CONTRIBUTION_PROFIT,
                contributionProfit(MetricCode.SETTLED_CONTRIBUTION_PROFIT,
                        settled, unitCost, fees, returns, advertising,
                        metrics.get(MetricCode.VARIABLE_TAX_ESTIMATE)));
        metrics.put(MetricCode.CONTRIBUTION_MARGIN,
                moneyRatio(MetricCode.CONTRIBUTION_MARGIN,
                        moneyOf(metrics.get(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT)),
                        completed.netAmount(),
                        merge(completed.evidence(),
                                evidenceOf(metrics.get(
                                        MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT)))));
        facts.latestPrice(listingVariantId, periodEnd).ifPresentOrElse(
                price -> putMoney(metrics, MetricCode.OBSERVED_SELLING_PRICE,
                        price.effectivePrice(), price.evidence()),
                () -> metrics.put(MetricCode.OBSERVED_SELLING_PRICE,
                        absent(MetricCode.OBSERVED_SELLING_PRICE,
                                ConfidenceState.INCOMPLETE)));
        metrics.put(MetricCode.MINIMUM_PRICE,
                breakEvenPrice(completed, unitCost, fees, returns, taxRate));
        metrics.put(MetricCode.DATA_COMPLETENESS,
                dataCompleteness(metrics, mappedVariant.isPresent()));

        return applyFreshness(metrics, periodEnd);
    }

    // -----------------------------------------------------------------------
    // Derived metrics
    // -----------------------------------------------------------------------

    /**
     * How many days the platform's stock would last at the window's sales rate.
     *
     * <p>Undefined when nothing sold: dividing available units by a rate of zero
     * says "forever", which is true and useless, and reporting it as a number
     * would put a healthy listing at the top of a stockout queue.
     */
    private static ComputedMetric stockCoverDays(StockSnapshot stock,
                                                 SalesTotals completed,
                                                 MetricWindow window) {
        FactEvidence evidence = merge(stock.evidence(), completed.evidence());
        if (!stock.evidence().usable() || !completed.available()) {
            return absent(MetricCode.STOCK_COVER_DAYS, confidenceFor(evidence));
        }
        if (completed.units() <= 0) {
            return undefined(MetricCode.STOCK_COVER_DAYS, evidence);
        }
        BigDecimal dailyRate = BigDecimal.valueOf(completed.units())
                .divide(BigDecimal.valueOf(window.days()), RATIO_SCALE, RoundingMode.HALF_UP);
        BigDecimal cover = BigDecimal.valueOf(stock.totalAvailable())
                .divide(dailyRate, DAY_SCALE, RoundingMode.HALF_UP);
        return new ComputedMetric(MetricCode.STOCK_COVER_DAYS, ValueState.AVAILABLE, cover,
                null, confidenceFor(evidence), evidence.oldestSourceTime(), inputs(evidence));
    }

    /**
     * The variable tax the profit definition subtracts.
     *
     * <p>This is explicitly an estimate: it applies a recorded rate to net sales
     * rather than reading a tax the marketplace charged. Every figure derived
     * from it inherits that state, which is why a price change cannot be
     * executed on the strength of it alone.
     */
    private static ComputedMetric variableTax(SalesTotals completed,
                                              Optional<FinanceInputSnapshot> taxRate) {
        if (!completed.available() || taxRate.isEmpty()
                || taxRate.get().rateValue() == null) {
            return absent(MetricCode.VARIABLE_TAX_ESTIMATE, ConfidenceState.INCOMPLETE);
        }
        Money estimate = completed.netAmount().times(taxRate.get().rateValue());
        List<MetricInput> inputs = new ArrayList<>(inputs(completed.evidence()));
        inputs.add(MetricInput.financeInput(taxRate.get().financeInputVersionId()));
        inputs.add(MetricInput.provenance(taxRate.get().provenanceId()));
        return new ComputedMetric(MetricCode.VARIABLE_TAX_ESTIMATE, ValueState.AVAILABLE,
                estimate.amount(), estimate.currencyCode(),
                ConfidenceState.ESTIMATED_EXPLAINED, completed.evidence().oldestSourceTime(),
                inputs);
    }

    /**
     * Contribution profit at one sale stage.
     *
     * <p>Every subtracted term must be present. A profit figure computed with a
     * missing cost or a missing fee is not a smaller profit; it is a wrong one,
     * and it would be wrong in the optimistic direction every time.
     */
    private static ComputedMetric contributionProfit(MetricCode metricCode,
                                                     SalesTotals sales,
                                                     Optional<CostSnapshot> unitCost,
                                                     FeeTotals fees,
                                                     ReturnTotals returns,
                                                     AdvertisingTotals advertising,
                                                     ComputedMetric variableTax) {
        if (!sales.available() || unitCost.isEmpty() || !fees.available()) {
            return absent(metricCode, ConfidenceState.INCOMPLETE);
        }
        Money net = sales.netAmount();
        Money cost = unitCost.get().unitCost().times(BigDecimal.valueOf(sales.units()));
        Money profit = net.minus(cost).minus(fees.total());
        List<MetricInput> inputs = new ArrayList<>(inputs(sales.evidence()));
        inputs.addAll(inputs(fees.evidence()));
        inputs.add(MetricInput.costVersion(unitCost.get().costVersionId()));
        inputs.add(MetricInput.provenance(unitCost.get().provenanceId()));

        if (returns.available() && returns.lossAmount() != null) {
            profit = profit.minus(returns.lossAmount());
            inputs.addAll(inputs(returns.evidence()));
        }
        if (advertising.available() && advertising.spendAmount() != null) {
            profit = profit.minus(advertising.spendAmount());
            inputs.addAll(inputs(advertising.evidence()));
        }
        boolean estimated = false;
        if (variableTax.valueState() == ValueState.AVAILABLE) {
            profit = profit.minus(Money.of(variableTax.numericValue(),
                    variableTax.currencyCode()));
            inputs.addAll(variableTax.inputs());
            estimated = true;
        }

        ConfidenceState confidence = estimated
                ? ConfidenceState.ESTIMATED_EXPLAINED
                : fees.settledOnly()
                        ? ConfidenceState.CANONICAL_CONFIRMED
                        : ConfidenceState.CANONICAL_PENDING_SETTLEMENT;
        return new ComputedMetric(metricCode, ValueState.AVAILABLE, profit.amount(),
                profit.currencyCode(), confidence,
                oldest(sales.evidence().oldestSourceTime(), fees.evidence().oldestSourceTime()),
                inputs);
    }

    /**
     * The unit price at which contribution profit is exactly zero.
     *
     * <p>The proportional fee rate is observed rather than assumed: it is what
     * the platform actually charged as a share of what it actually sold. A rate
     * at or above one leaves no price that breaks even, which is undefined
     * rather than infinite.
     */
    private static ComputedMetric breakEvenPrice(SalesTotals completed,
                                                 Optional<CostSnapshot> unitCost,
                                                 FeeTotals fees,
                                                 ReturnTotals returns,
                                                 Optional<FinanceInputSnapshot> taxRate) {
        if (unitCost.isEmpty() || !completed.available() || completed.units() <= 0
                || !fees.available()) {
            return absent(MetricCode.MINIMUM_PRICE, ConfidenceState.INCOMPLETE);
        }
        BigDecimal net = completed.netAmount().amount();
        if (net.signum() <= 0) {
            return undefined(MetricCode.MINIMUM_PRICE, completed.evidence());
        }
        BigDecimal feeRate = fees.total().amount()
                .divide(net, RATIO_SCALE, RoundingMode.HALF_UP);
        BigDecimal tax = taxRate.map(FinanceInputSnapshot::rateValue).orElse(BigDecimal.ZERO);
        BigDecimal denominator = BigDecimal.ONE.subtract(feeRate).subtract(tax);
        if (denominator.signum() <= 0) {
            return undefined(MetricCode.MINIMUM_PRICE, completed.evidence());
        }

        BigDecimal units = BigDecimal.valueOf(completed.units());
        BigDecimal returnLossPerUnit = returns.available() && returns.lossAmount() != null
                ? returns.lossAmount().amount().divide(units, RATIO_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal numerator = unitCost.get().unitCost().amount().add(returnLossPerUnit);
        Money price = Money.of(numerator.divide(denominator, Money.SCALE, RoundingMode.HALF_UP),
                unitCost.get().unitCost().currencyCode());

        List<MetricInput> inputs = new ArrayList<>(inputs(completed.evidence()));
        inputs.addAll(inputs(fees.evidence()));
        inputs.add(MetricInput.costVersion(unitCost.get().costVersionId()));
        taxRate.ifPresent(rate ->
                inputs.add(MetricInput.financeInput(rate.financeInputVersionId())));
        ConfidenceState confidence = taxRate.isPresent()
                ? ConfidenceState.ESTIMATED_EXPLAINED
                : ConfidenceState.CANONICAL_PENDING_SETTLEMENT;
        return new ComputedMetric(MetricCode.MINIMUM_PRICE, ValueState.AVAILABLE,
                price.amount(), price.currencyCode(), confidence,
                completed.evidence().oldestSourceTime(), inputs);
    }

    /**
     * The share of the profit definition's inputs that resolved canonically.
     *
     * <p>This is the number the blocking rule compares against, so it counts
     * inputs rather than weighting them: an operator asking why a listing is
     * blocked needs to see which of a small, fixed set is missing.
     */
    private static ComputedMetric dataCompleteness(Map<MetricCode, ComputedMetric> metrics,
                                                   boolean mappingResolved) {
        List<MetricCode> profitInputs = List.of(
                MetricCode.COMPLETED_NET_SALES, MetricCode.UNIT_COST,
                MetricCode.PLATFORM_FEES, MetricCode.RETURN_LOSS,
                MetricCode.AD_SPEND, MetricCode.VARIABLE_TAX_ESTIMATE);
        long resolved = profitInputs.stream()
                .map(metrics::get)
                .filter(metric -> metric != null && metric.valueState() == ValueState.AVAILABLE)
                .count();
        BigDecimal share = BigDecimal.valueOf(resolved)
                .divide(BigDecimal.valueOf(PROFIT_INPUT_COUNT), RATIO_SCALE,
                        RoundingMode.HALF_UP);
        // An unresolved mapping makes cost and therefore profit unattributable,
        // so completeness is zero regardless of how many marketplace facts
        // arrived. Reporting a high share for an unmappable listing would hide
        // exactly the condition the blocking rule exists to catch.
        BigDecimal value = mappingResolved ? share : BigDecimal.ZERO;
        return new ComputedMetric(MetricCode.DATA_COMPLETENESS, ValueState.AVAILABLE, value,
                null, mappingResolved
                        ? ConfidenceState.CANONICAL_CONFIRMED : ConfidenceState.INCOMPLETE,
                null, List.of());
    }

    // -----------------------------------------------------------------------
    // Construction helpers
    // -----------------------------------------------------------------------

    private static void putCount(Map<MetricCode, ComputedMetric> metrics,
                                 MetricCode metricCode,
                                 Long value,
                                 FactEvidence evidence) {
        metrics.put(metricCode, value == null
                ? absent(metricCode, confidenceFor(evidence))
                : new ComputedMetric(metricCode, ValueState.AVAILABLE,
                        BigDecimal.valueOf(value), null, confidenceFor(evidence),
                        evidence.oldestSourceTime(), inputs(evidence)));
    }

    private static void putMoney(Map<MetricCode, ComputedMetric> metrics,
                                 MetricCode metricCode,
                                 Money value,
                                 FactEvidence evidence) {
        metrics.put(metricCode, value == null
                ? absent(metricCode, confidenceFor(evidence))
                : new ComputedMetric(metricCode, ValueState.AVAILABLE, value.amount(),
                        value.currencyCode(), confidenceFor(evidence),
                        evidence.oldestSourceTime(), inputs(evidence)));
    }

    private static ComputedMetric ratio(MetricCode metricCode,
                                        Long numerator,
                                        Long denominator,
                                        FactEvidence evidence) {
        if (numerator == null || denominator == null) {
            return absent(metricCode, confidenceFor(evidence));
        }
        if (denominator <= 0) {
            return undefined(metricCode, evidence);
        }
        BigDecimal value = BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATIO_SCALE, RoundingMode.HALF_UP);
        return new ComputedMetric(metricCode, ValueState.AVAILABLE, value, null,
                confidenceFor(evidence), evidence.oldestSourceTime(), inputs(evidence));
    }

    private static ComputedMetric moneyRatio(MetricCode metricCode,
                                             Money numerator,
                                             Money denominator,
                                             FactEvidence evidence) {
        if (numerator == null || denominator == null) {
            return absent(metricCode, confidenceFor(evidence));
        }
        if (denominator.amount().signum() == 0) {
            return undefined(metricCode, evidence);
        }
        BigDecimal value = numerator.amount()
                .divide(denominator.amount(), RATIO_SCALE, RoundingMode.HALF_UP);
        return new ComputedMetric(metricCode, ValueState.AVAILABLE, value, null,
                confidenceFor(evidence), evidence.oldestSourceTime(), inputs(evidence));
    }

    private static ComputedMetric absent(MetricCode metricCode, ConfidenceState confidence) {
        return new ComputedMetric(metricCode, ValueState.NOT_AVAILABLE, null, null,
                confidence, null, List.of());
    }

    private static ComputedMetric undefined(MetricCode metricCode, FactEvidence evidence) {
        return new ComputedMetric(metricCode, ValueState.UNDEFINED, null, null,
                confidenceFor(evidence), evidence.oldestSourceTime(), inputs(evidence));
    }

    private static ConfidenceState confidenceFor(FactEvidence evidence) {
        if (evidence.currencyConflict()) {
            return ConfidenceState.CONFLICTED;
        }
        return evidence.present()
                ? ConfidenceState.CANONICAL_CONFIRMED : ConfidenceState.INCOMPLETE;
    }

    private static List<MetricInput> inputs(FactEvidence evidence) {
        return evidence.provenanceIds().stream().map(MetricInput::provenance).toList();
    }

    private static FactEvidence merge(FactEvidence first, FactEvidence second) {
        List<UUID> combined = new ArrayList<>(first.provenanceIds());
        combined.addAll(second.provenanceIds());
        Instant oldest = oldest(first.oldestSourceTime(), second.oldestSourceTime());
        return first.currencyConflict() || second.currencyConflict()
                ? FactEvidence.conflicted(combined.stream().distinct().toList(), oldest)
                : FactEvidence.of(combined.stream().distinct().toList(), oldest);
    }

    private static FactEvidence evidenceOf(ComputedMetric metric) {
        return FactEvidence.of(
                metric.inputs().stream()
                        .filter(input -> input.kind() == MetricInput.Kind.FACT_PROVENANCE)
                        .map(MetricInput::referenceId)
                        .toList(),
                metric.oldestSourceTime());
    }

    private static Money moneyOf(ComputedMetric metric) {
        return metric.valueState() == ValueState.AVAILABLE && metric.currencyCode() != null
                ? Money.of(metric.numericValue(), metric.currencyCode()) : null;
    }

    private static Instant oldest(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isBefore(second) ? first : second;
    }

    /**
     * Downgrade any value whose freshest contributing fact is older than its
     * domain allows.
     *
     * <p>Freshness is applied after computation rather than during it, so one
     * rule governs every metric and a new metric cannot be added without
     * inheriting it.
     */
    private Map<MetricCode, ComputedMetric> applyFreshness(
            Map<MetricCode, ComputedMetric> metrics, Instant periodEnd) {
        Map<MetricCode, ComputedMetric> adjusted = new EnumMap<>(MetricCode.class);
        metrics.forEach((code, metric) -> {
            Duration target = freshnessTarget(code);
            boolean stale = metric.oldestSourceTime() != null
                    && Duration.between(metric.oldestSourceTime(), periodEnd).compareTo(target)
                            > 0;
            adjusted.put(code, stale && metric.confidenceState().sufficientForWrite()
                    ? new ComputedMetric(metric.metricCode(), metric.valueState(),
                            metric.numericValue(), metric.currencyCode(),
                            ConfidenceState.STALE, metric.oldestSourceTime(), metric.inputs())
                    : metric);
        });
        return Map.copyOf(adjusted);
    }

    private Duration freshnessTarget(MetricCode metricCode) {
        return switch (metricCode.domain()) {
            case FUNNEL -> properties.getFunnelFreshness();
            case SALES, PROFIT -> properties.getSalesFreshness();
            case RETURNS -> properties.getReturnsFreshness();
            case INVENTORY -> properties.getInventoryFreshness();
            case ADVERTISING -> properties.getAdvertisingFreshness();
            case COST, QUALITY -> properties.getCostFreshness();
        };
    }
}

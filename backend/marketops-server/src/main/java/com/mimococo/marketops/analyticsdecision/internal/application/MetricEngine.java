package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsCalculator;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsProfile;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsResolution;
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
    private static final int PROFIT_INPUT_COUNT = 8;

    private final OperatingFactQuery facts;
    private final ListingIdentityDirectory listings;
    private final PriceEconomicsQuery economics;
    private final AnalyticsProperties properties;

    MetricEngine(OperatingFactQuery facts,
                 ListingIdentityDirectory listings,
                 PriceEconomicsQuery economics,
                 AnalyticsProperties properties) {
        this.facts = facts;
        this.listings = listings;
        this.economics = economics;
        this.properties = properties;
    }

    /**
     * Compute every metric for one platform listing variant over one window.
     *
     * @param organizationId organization the subject belongs to
     * @param storeId store the subject sits on
     * @param listingVariantId the subject
     * @param window the observation window
     * @param factWindow the one exact half-open window owned by the calculation run
     */
    public Map<MetricCode, ComputedMetric> compute(UUID organizationId,
                                                   UUID storeId,
                                                   UUID listingVariantId,
                                                   MetricWindow window,
                                                   FactWindow factWindow) {
        Instant periodEnd = factWindow.periodEnd();
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

        var listingContext = listings.variantContext(listingVariantId, periodEnd);
        Optional<UUID> mappedVariant = listingContext
                .filter(context -> context.mapped() && !context.conflictOpen())
                .map(context -> context.productVariantId());
        Optional<UUID> mappingId = listingContext
                .filter(context -> context.mapped() && !context.conflictOpen())
                .map(context -> context.mappingId());
        List<String> fulfillmentModes = listingContext
                .map(context -> economics.activeFulfillmentModes(context.storeId(), periodEnd))
                .orElseGet(List::of);
        PriceEconomicsResolution economicsResolution = listingContext.isPresent()
                && fulfillmentModes.size() == 1
                ? economics.resolveProfile(organizationId,
                        listingContext.orElseThrow().platformCode(),
                        listingContext.orElseThrow().marketplaceAccountId(), storeId,
                        fulfillmentModes.getFirst(), periodEnd)
                : PriceEconomicsResolution.unavailable(
                        fulfillmentModes.size() > 1
                                ? PriceEconomicsResolution.Status.AMBIGUOUS
                                : PriceEconomicsResolution.Status.MISSING,
                        "periodic-metric-fulfillment-scope-count=" + fulfillmentModes.size());
        PriceEconomicsCalculator.HistoricalCoverage feeCoverage =
                economicsResolution.available()
                        ? PriceEconomicsCalculator.historicalCoverage(
                                economicsResolution.profile(), fees)
                        : new PriceEconomicsCalculator.HistoricalCoverage(false, Map.of(),
                                List.of("ECONOMICS_PROFILE_"
                                        + economicsResolution.status()));
        Optional<CostSnapshot> unitCost = mappedVariant
                .flatMap(variantId -> facts.unitCost(variantId, periodEnd))
                .filter(cost -> cost.costVersionId() != null && cost.provenanceId() != null
                        && cost.unitCost() != null && cost.unitCost().amount().signum() >= 0
                        && cost.effectiveFrom() != null && cost.effectiveFrom().isBefore(periodEnd));
        InternalStockSnapshot internalStock = mappedVariant
                .map(variantId -> facts.internalStock(variantId, periodEnd))
                .orElseGet(InternalStockSnapshot::absent);
        Optional<FinanceInputSnapshot> taxRate = facts.financeInput(organizationId,
                "VARIABLE_TAX_RATE", storeId, mappedVariant.orElse(null), periodEnd);
        Optional<FinanceInputSnapshot> requiredProfit = facts.financeInput(organizationId,
                "REQUIRED_PROFIT_PER_UNIT", storeId, mappedVariant.orElse(null), periodEnd);
        Optional<FinanceInputSnapshot> safetyBuffer = facts.financeInput(organizationId,
                "SAFETY_BUFFER_PER_UNIT", storeId, mappedVariant.orElse(null), periodEnd);

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

        // The owning fact authority resolves ACTIVE cost versions inside their
        // effective interval. effectiveFrom is validity, not an observation's
        // source update time; ageing it would expire an unchanged valid version.
        // Preserve its exact identity, while run evidence records when this
        // canonical calculation actually revalidated it. CostSnapshot supplies
        // no observation timestamp, so no source update time is fabricated.
        metrics.put(MetricCode.UNIT_COST, unitCost
                .map(cost -> new ComputedMetric(MetricCode.UNIT_COST, ValueState.AVAILABLE,
                        cost.unitCost().amount(), cost.unitCost().currencyCode(),
                        ConfidenceState.CANONICAL_CONFIRMED, null,
                        List.of(MetricInput.costVersion(cost.costVersionId()),
                                MetricInput.provenance(cost.provenanceId())),
                        List.of("costEffectiveFrom=" + cost.effectiveFrom())))
                .orElseGet(() -> absent(MetricCode.UNIT_COST, ConfidenceState.INCOMPLETE)));

        putMoney(metrics, MetricCode.PLATFORM_FEES,
                feeCoverage.complete() && fees.platformFeesAvailable() ? fees.total() : null,
                fees.evidence());
        putMoney(metrics, MetricCode.RETURN_LOSS,
                returns.available() ? returns.lossAmount() : null, returns.evidence());
        metrics.put(MetricCode.VARIABLE_TAX_ESTIMATE,
                variableTax(completed, taxRate));

        metrics.put(MetricCode.PLATFORM_FEES_PER_UNIT,
                perUnit(MetricCode.PLATFORM_FEES_PER_UNIT,
                        feeCoverage.complete() ? fees.total() : null, fees.evidence(),
                        completed, fees.settledOnly(), feeCoverage));
        metrics.put(MetricCode.RETURN_LOSS_PER_UNIT,
                perUnit(MetricCode.RETURN_LOSS_PER_UNIT, returns.lossAmount(),
                        returns.evidence(), completed, true));
        metrics.put(MetricCode.AD_SPEND_PER_UNIT,
                perUnit(MetricCode.AD_SPEND_PER_UNIT, advertising.spendAmount(),
                        advertising.evidence(), completed, true));
        metrics.put(MetricCode.VARIABLE_TAX_PER_UNIT,
                perUnit(MetricCode.VARIABLE_TAX_PER_UNIT, fees.variableTax(),
                        fees.evidence(), completed, fees.settledOnly()));
        metrics.put(MetricCode.REQUIRED_PROFIT_PER_UNIT,
                financeAmount(MetricCode.REQUIRED_PROFIT_PER_UNIT, requiredProfit));
        metrics.put(MetricCode.SAFETY_BUFFER_PER_UNIT,
                financeAmount(MetricCode.SAFETY_BUFFER_PER_UNIT, safetyBuffer));

        metrics.put(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                contributionProfit(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                        completed, unitCost, fees, returns, advertising,
                        metrics.get(MetricCode.VARIABLE_TAX_ESTIMATE), feeCoverage, false));
        metrics.put(MetricCode.SETTLED_CONTRIBUTION_PROFIT,
                contributionProfit(MetricCode.SETTLED_CONTRIBUTION_PROFIT,
                        settled, unitCost, fees, returns, advertising,
                        aggregateActualTax(fees), feeCoverage, true));
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
        PriceEconomicsCalculator.Solution priceSolution = solvePrices(
                economicsResolution, metrics);
        metrics.put(MetricCode.BREAK_EVEN_PRICE, projectedPriceMetric(
                MetricCode.BREAK_EVEN_PRICE, economicsResolution, priceSolution,
                priceSolution.breakEvenPrice(), metrics));
        metrics.put(MetricCode.MINIMUM_PRICE, projectedPriceMetric(
                MetricCode.MINIMUM_PRICE, economicsResolution, priceSolution,
                priceSolution.minimumPrice(), metrics));
        metrics.put(MetricCode.DATA_COMPLETENESS,
                dataCompleteness(metrics, mappingId));

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
                                                     ComputedMetric variableTax,
                                                     PriceEconomicsCalculator.HistoricalCoverage
                                                             feeCoverage,
                                                     boolean requireSettledFees) {
        boolean salesAvailable = sales.available() && sales.netAmount() != null;
        boolean costAvailable = unitCost.isPresent();
        boolean feesAvailable = fees.platformFeesAvailable() && feeCoverage.complete();
        boolean returnsAvailable = returns.available() && returns.lossAmount() != null;
        boolean advertisingAvailable = advertising.available()
                && advertising.spendAmount() != null;
        boolean taxAvailable = variableTax.valueState() == ValueState.AVAILABLE
                && variableTax.numericValue() != null && variableTax.currencyCode() != null;

        List<String> states = new ArrayList<>(List.of(
                "sales=" + salesAvailable,
                "cost=" + costAvailable,
                "platformFees=" + feesAvailable,
                "returnLoss=" + returnsAvailable,
                "advertising=" + advertisingAvailable,
                "tax=" + taxAvailable,
                "settledFees=" + fees.settledOnly()));
        feeCoverage.familyStates().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> states.add("feeFamily:" + entry.getKey()
                        + '=' + entry.getValue()));
        feeCoverage.reasons().forEach(reason -> states.add("feeCoverage:" + reason));
        List<MetricInput> metricInputs = new ArrayList<>();
        metricInputs.addAll(inputs(sales.evidence()));
        metricInputs.addAll(inputs(fees.evidence()));
        metricInputs.addAll(inputs(returns.evidence()));
        metricInputs.addAll(inputs(advertising.evidence()));
        metricInputs.addAll(variableTax.inputs());
        unitCost.ifPresent(cost -> {
            metricInputs.add(MetricInput.costVersion(cost.costVersionId()));
            metricInputs.add(MetricInput.provenance(cost.provenanceId()));
        });

        if (!salesAvailable || !costAvailable || !feesAvailable || !returnsAvailable
                || !advertisingAvailable || !taxAvailable) {
            return absent(metricCode, ConfidenceState.INCOMPLETE, metricInputs, states);
        }

        Money tax = Money.of(variableTax.numericValue(), variableTax.currencyCode());
        Money cost = unitCost.orElseThrow().unitCost()
                .times(BigDecimal.valueOf(sales.units()));
        List<Money> amounts = List.of(sales.netAmount(), cost, fees.total(),
                returns.lossAmount(), advertising.spendAmount(), tax);
        if (!sameCurrency(amounts)) {
            return absent(metricCode, ConfidenceState.CONFLICTED, metricInputs,
                    append(states, "currencyCompatible=false"));
        }

        Money profit = sales.netAmount().minus(cost).minus(fees.total())
                .minus(returns.lossAmount()).minus(advertising.spendAmount()).minus(tax);
        ConfidenceState confidence;
        if (variableTax.confidenceState() == ConfidenceState.ESTIMATED_EXPLAINED) {
            confidence = ConfidenceState.ESTIMATED_EXPLAINED;
        } else if (requireSettledFees && !fees.settledOnly()) {
            confidence = ConfidenceState.CANONICAL_PENDING_SETTLEMENT;
        } else {
            confidence = ConfidenceState.CANONICAL_CONFIRMED;
        }
        return new ComputedMetric(metricCode, ValueState.AVAILABLE, profit.amount(),
                profit.currencyCode(), confidence, oldestSource(
                        sales.evidence().oldestSourceTime(), fees.evidence().oldestSourceTime(),
                        returns.evidence().oldestSourceTime(),
                        advertising.evidence().oldestSourceTime(),
                        variableTax.oldestSourceTime()),
                distinct(metricInputs), append(states, "currencyCompatible=true"));
    }

    /** An actual tax aggregate used by settled profit; it is never exposed as a metric. */
    private static ComputedMetric aggregateActualTax(FeeTotals fees) {
        if (!fees.variableTaxAvailable()) {
            return absent(MetricCode.VARIABLE_TAX_PER_UNIT, ConfidenceState.INCOMPLETE,
                    inputs(fees.evidence()), List.of("actualTax=false"));
        }
        return new ComputedMetric(MetricCode.VARIABLE_TAX_PER_UNIT, ValueState.AVAILABLE,
                fees.variableTax().amount(), fees.variableTax().currencyCode(),
                fees.settledOnly() ? ConfidenceState.CANONICAL_CONFIRMED
                        : ConfidenceState.CANONICAL_PENDING_SETTLEMENT,
                fees.evidence().oldestSourceTime(), inputs(fees.evidence()),
                List.of("actualTax=true", "settled=" + fees.settledOnly()));
    }

    /** Convert one explicitly published whole-window amount to a per-unit amount. */
    private static ComputedMetric perUnit(MetricCode metricCode,
                                          Money total,
                                          FactEvidence evidence,
                                          SalesTotals completed,
                                          boolean settledOnly) {
        return perUnit(metricCode, total, evidence, completed, settledOnly, null);
    }

    /** Convert a covered historical amount while retaining every family state. */
    private static ComputedMetric perUnit(MetricCode metricCode,
                                          Money total,
                                          FactEvidence evidence,
                                          SalesTotals completed,
                                          boolean settledOnly,
                                          PriceEconomicsCalculator.HistoricalCoverage coverage) {
        List<MetricInput> metricInputs = new ArrayList<>(inputs(evidence));
        metricInputs.addAll(inputs(completed.evidence()));
        List<String> states = new ArrayList<>(List.of("totalPresent=" + (total != null),
                "unitsPresent=" + completed.available(), "settled=" + settledOnly));
        if (coverage != null) {
            coverage.familyStates().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> states.add("feeFamily:" + entry.getKey()
                            + '=' + entry.getValue()));
            coverage.reasons().forEach(reason -> states.add("feeCoverage:" + reason));
        }
        if (total == null || !evidence.usable() || !completed.available()) {
            return absent(metricCode, confidenceFor(merge(evidence, completed.evidence())),
                    metricInputs, states);
        }
        if (completed.units() <= 0) {
            return new ComputedMetric(metricCode, ValueState.UNDEFINED, null, null,
                    confidenceFor(merge(evidence, completed.evidence())),
                    oldest(evidence.oldestSourceTime(), completed.evidence().oldestSourceTime()),
                    distinct(metricInputs), append(states, "unitsPositive=false"));
        }
        BigDecimal value = total.amount().divide(BigDecimal.valueOf(completed.units()),
                Money.SCALE, RoundingMode.HALF_UP);
        return new ComputedMetric(metricCode, ValueState.AVAILABLE, value,
                total.currencyCode(), settledOnly ? ConfidenceState.CANONICAL_CONFIRMED
                        : ConfidenceState.CANONICAL_PENDING_SETTLEMENT,
                oldest(evidence.oldestSourceTime(), completed.evidence().oldestSourceTime()),
                distinct(metricInputs), append(states, "unitsPositive=true"));
    }

    /** A versioned company-owned amount; no default is permitted. */
    private static ComputedMetric financeAmount(MetricCode metricCode,
                                                Optional<FinanceInputSnapshot> input) {
        if (input.isEmpty() || input.get().amountValue() == null) {
            return absent(metricCode, ConfidenceState.INCOMPLETE, List.of(),
                    List.of("amountInput=false"));
        }
        FinanceInputSnapshot value = input.get();
        return new ComputedMetric(metricCode, ValueState.AVAILABLE,
                value.amountValue().amount(), value.amountValue().currencyCode(),
                ConfidenceState.CANONICAL_CONFIRMED, value.effectiveFrom(),
                List.of(MetricInput.financeInput(value.financeInputVersionId()),
                        MetricInput.provenance(value.provenanceId())),
                List.of("amountInput=true"));
    }

    /** Solve current profile economics without substituting historical averages. */
    private static PriceEconomicsCalculator.Solution solvePrices(
            PriceEconomicsResolution resolution,
            Map<MetricCode, ComputedMetric> metrics) {
        if (!resolution.available()) {
            return PriceEconomicsCalculator.Solution.unavailable(
                    "ECONOMICS_PROFILE_" + resolution.status());
        }
        Money unitCost = moneyOf(metrics.get(MetricCode.UNIT_COST));
        Money requiredProfit = moneyOf(metrics.get(MetricCode.REQUIRED_PROFIT_PER_UNIT));
        Money safetyBuffer = moneyOf(metrics.get(MetricCode.SAFETY_BUFFER_PER_UNIT));
        return PriceEconomicsCalculator.solve(resolution.profile(), unitCost,
                requiredProfit, safetyBuffer);
    }

    /** Bind the solved value to the profile and exact component tiers it consumes. */
    private static ComputedMetric projectedPriceMetric(
            MetricCode metricCode,
            PriceEconomicsResolution resolution,
            PriceEconomicsCalculator.Solution solution,
            BigDecimal value,
            Map<MetricCode, ComputedMetric> metrics) {
        List<MetricInput> metricInputs = new ArrayList<>();
        List<String> states = new ArrayList<>();
        states.add("economicsResolution=" + resolution.status());
        states.addAll(solution.reasons().stream().map(reason -> "solver=" + reason).toList());
        for (MetricCode inputCode : List.of(MetricCode.UNIT_COST,
                MetricCode.REQUIRED_PROFIT_PER_UNIT, MetricCode.SAFETY_BUFFER_PER_UNIT)) {
            ComputedMetric input = metrics.get(inputCode);
            if (input != null) {
                metricInputs.addAll(input.inputs());
                states.add(inputCode + "=" + input.valueState() + ':'
                        + input.confidenceState());
            }
        }
        if (!resolution.available() || !solution.available() || value == null) {
            return absent(metricCode, ConfidenceState.INCOMPLETE, metricInputs, states);
        }
        PriceEconomicsProfile profile = resolution.profile();
        metricInputs.add(MetricInput.economicsProfile(profile.profileId()));
        PriceEconomicsCalculator.Projection projection =
                PriceEconomicsCalculator.project(profile, value);
        projection.componentIds().forEach(id ->
                metricInputs.add(MetricInput.economicsComponent(id)));
        states.add("profileId=" + profile.profileId());
        states.add("profileVersion=" + profile.profileVersion());
        states.add("fulfillmentMode=" + profile.fulfillmentModeCode());
        projection.familyCoverage().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> states.add("projectedFamily:" + entry.getKey()
                        + '=' + entry.getValue()));
        Instant sourceTime = List.of(
                        metrics.get(MetricCode.UNIT_COST),
                        metrics.get(MetricCode.REQUIRED_PROFIT_PER_UNIT),
                        metrics.get(MetricCode.SAFETY_BUFFER_PER_UNIT))
                .stream().map(ComputedMetric::oldestSourceTime)
                .filter(java.util.Objects::nonNull)
                .reduce(profile.verifiedAt(), (left, right) ->
                        left.isBefore(right) ? left : right);
        return new ComputedMetric(metricCode, ValueState.AVAILABLE, value,
                profile.currencyCode(), ConfidenceState.CANONICAL_CONFIRMED,
                sourceTime, distinct(metricInputs), states);
    }

    /**
     * The share of the profit definition's inputs that resolved canonically.
     *
     * <p>This is the number the blocking rule compares against, so it counts
     * inputs rather than weighting them: an operator asking why a listing is
     * blocked needs to see which of a small, fixed set is missing.
     */
    private static ComputedMetric dataCompleteness(Map<MetricCode, ComputedMetric> metrics,
                                                   Optional<UUID> mappingId) {
        List<MetricCode> profitInputs = List.of(
                MetricCode.COMPLETED_NET_SALES, MetricCode.UNIT_COST,
                MetricCode.PLATFORM_FEES_PER_UNIT, MetricCode.RETURN_LOSS_PER_UNIT,
                MetricCode.AD_SPEND_PER_UNIT, MetricCode.VARIABLE_TAX_PER_UNIT,
                MetricCode.REQUIRED_PROFIT_PER_UNIT, MetricCode.SAFETY_BUFFER_PER_UNIT);
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
        BigDecimal value = mappingId.isPresent() ? share : BigDecimal.ZERO;
        List<MetricInput> metricInputs = profitInputs.stream().map(metrics::get)
                .flatMap(metric -> metric.inputs().stream()).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        mappingId.ifPresent(id -> metricInputs.add(MetricInput.listingMapping(id)));
        List<String> states = profitInputs.stream().map(code -> {
            ComputedMetric metric = metrics.get(code);
            return code + "=" + metric.valueState() + ":" + metric.confidenceState();
        }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        states.add("mapping=" + mappingId.isPresent());
        Instant sourceTime = profitInputs.stream().map(metrics::get)
                .map(ComputedMetric::oldestSourceTime).filter(java.util.Objects::nonNull)
                .min(Instant::compareTo).orElse(null);
        return new ComputedMetric(MetricCode.DATA_COMPLETENESS, ValueState.AVAILABLE, value,
                null, mappingId.isPresent()
                        ? ConfidenceState.CANONICAL_CONFIRMED : ConfidenceState.INCOMPLETE,
                sourceTime, distinct(metricInputs), states);
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

    private static ComputedMetric absent(MetricCode metricCode,
                                         ConfidenceState confidence,
                                         List<MetricInput> inputs,
                                         List<String> identityComponents) {
        return new ComputedMetric(metricCode, ValueState.NOT_AVAILABLE, null, null,
                confidence, null, distinct(inputs), identityComponents);
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

    private static Instant oldestSource(Instant... values) {
        return java.util.Arrays.stream(values).filter(java.util.Objects::nonNull)
                .min(Instant::compareTo).orElse(null);
    }

    private static boolean sameCurrency(List<Money> amounts) {
        return amounts.stream().map(Money::currencyCode).distinct().count() == 1;
    }

    private static List<MetricInput> distinct(List<MetricInput> inputs) {
        return inputs.stream().distinct().toList();
    }

    private static List<String> append(List<String> values, String value) {
        List<String> combined = new ArrayList<>(values);
        combined.add(value);
        return List.copyOf(combined);
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
                            ConfidenceState.STALE, metric.oldestSourceTime(), metric.inputs(),
                            metric.identityComponents())
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

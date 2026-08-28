package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.operatingfacts.AdvertisingTotals;
import com.mimococo.marketops.operatingfacts.CostSnapshot;
import com.mimococo.marketops.operatingfacts.FactEvidence;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.FeeTotals;
import com.mimococo.marketops.operatingfacts.FinanceInputSnapshot;
import com.mimococo.marketops.operatingfacts.InternalStockSnapshot;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.PriceSnapshot;
import com.mimococo.marketops.operatingfacts.ReturnTotals;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.operatingfacts.SalesTotals;
import com.mimococo.marketops.operatingfacts.StockSnapshot;
import com.mimococo.marketops.operatingfacts.TrafficTotals;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.FactQueryRepository;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns aggregated fact rows into answers that carry their own provenance,
 * freshness and trustworthiness.
 *
 * <p>Two rules run through every method. Absence is preserved: nothing here
 * substitutes zero for a measure no source published, because a window with no
 * sales and a window with no data are different business situations. And a
 * currency disagreement withholds the number rather than blending it: a total
 * summed across currencies is a confident figure that means nothing, so the
 * disagreement is reported and the caller records the metric as conflicted.
 *
 * <p>The advertising charge a platform reports as a fee is separated from the
 * rest of the fee mix, because the profit definition subtracts advertising on
 * its own and counting it twice would understate profit by exactly the ad spend.
 */
@Service
public class OperatingFactService implements OperatingFactQuery {

    /** The fee category the profit definition subtracts separately. */
    private static final String ADVERTISING_CATEGORY = "ADVERTISING";

    private final FactQueryRepository facts;

    OperatingFactService(FactQueryRepository facts) {
        this.facts = facts;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PriceSnapshot> latestPrice(UUID platformListingVariantId, Instant asOf) {
        return facts.latestPrice(platformListingVariantId, asOf).map(row -> new PriceSnapshot(
                row.id(),
                row.observedAt(),
                money(row.listPrice(), row.currencyCode()),
                money(row.sellingPrice(), row.currencyCode()),
                money(row.discountPrice(), row.currencyCode()),
                row.promotionActive(),
                FactEvidence.of(List.of(row.provenanceId()), row.sourceTime())));
    }

    @Override
    @Transactional(readOnly = true)
    public StockSnapshot latestStock(UUID platformListingVariantId, Instant asOf) {
        List<FactQueryRepository.StockRow> rows =
                facts.latestStockByMode(platformListingVariantId, asOf);
        if (rows.isEmpty()) {
            return StockSnapshot.absent();
        }
        Map<String, Integer> byMode = new LinkedHashMap<>();
        for (FactQueryRepository.StockRow row : rows) {
            // A mode whose quantity the source did not publish is left out
            // rather than entered as zero, so a caller can tell "none in stock"
            // from "the source said nothing about this mode".
            if (row.availableQuantity() != null) {
                byMode.put(row.fulfillmentModeCode(), row.availableQuantity());
            }
        }
        return new StockSnapshot(
                rows.stream().map(FactQueryRepository.StockRow::observedAt)
                        .max(Instant::compareTo).orElse(null),
                byMode,
                FactEvidence.of(
                        rows.stream().map(FactQueryRepository.StockRow::provenanceId).toList(),
                        oldest(rows.stream().map(FactQueryRepository.StockRow::sourceTime))));
    }

    @Override
    @Transactional(readOnly = true)
    public TrafficTotals traffic(UUID platformListingVariantId, FactWindow window) {
        return facts.traffic(platformListingVariantId, window.periodStart(), window.periodEnd())
                .map(row -> new TrafficTotals(
                        row.impressions(), row.clicks(), row.visits(), row.addToCart(),
                        row.orderedUnits(),
                        FactEvidence.of(row.provenanceIds(), row.oldestSourceTime())))
                .orElseGet(TrafficTotals::absent);
    }

    @Override
    @Transactional(readOnly = true)
    public SalesTotals sales(UUID platformListingVariantId,
                             SaleStage stage,
                             Integer retentionWindowDays,
                             FactWindow window) {
        List<FactQueryRepository.MoneyGroupRow> rows = facts.sales(
                platformListingVariantId, stage.name(), retentionWindowDays,
                window.periodStart(), window.periodEnd());
        if (rows.isEmpty()) {
            return SalesTotals.absent();
        }
        if (rows.size() > 1) {
            return new SalesTotals(0L, null, null, conflicted(rows));
        }
        FactQueryRepository.MoneyGroupRow row = rows.getFirst();
        return new SalesTotals(
                row.quantity(),
                money(row.primaryAmount(), row.currencyCode()),
                money(row.secondaryAmount(), row.currencyCode()),
                FactEvidence.of(row.provenanceIds(), row.oldestSourceTime()));
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnTotals returns(UUID platformListingVariantId, FactWindow window) {
        List<FactQueryRepository.MoneyGroupRow> rows = facts.returns(
                platformListingVariantId, window.periodStart(), window.periodEnd());
        if (rows.isEmpty()) {
            return ReturnTotals.absent();
        }
        Map<String, Long> byReason = new LinkedHashMap<>();
        facts.returnsByReason(platformListingVariantId, window.periodStart(), window.periodEnd())
                .forEach(reason -> byReason.put(reason.reasonCategory(), reason.quantity()));
        if (rows.size() > 1) {
            return new ReturnTotals(0L, null, null, byReason, conflicted(rows));
        }
        FactQueryRepository.MoneyGroupRow row = rows.getFirst();
        return new ReturnTotals(
                row.quantity(),
                money(row.primaryAmount(), row.currencyCode()),
                money(row.secondaryAmount(), row.currencyCode()),
                byReason,
                FactEvidence.of(row.provenanceIds(), row.oldestSourceTime()));
    }

    @Override
    @Transactional(readOnly = true)
    public FeeTotals fees(UUID platformListingVariantId, FactWindow window) {
        List<FactQueryRepository.FeeGroupRow> rows = facts.fees(
                platformListingVariantId, window.periodStart(), window.periodEnd());
        if (rows.isEmpty()) {
            return FeeTotals.absent();
        }
        List<String> currencies = rows.stream()
                .map(FactQueryRepository.FeeGroupRow::currencyCode).distinct().toList();
        List<UUID> provenance = rows.stream()
                .flatMap(row -> row.provenanceIds().stream()).distinct().toList();
        Instant oldest = oldest(rows.stream()
                .map(FactQueryRepository.FeeGroupRow::oldestSourceTime));
        if (currencies.size() > 1) {
            return new FeeTotals(null, null, Map.of(), false,
                    FactEvidence.conflicted(provenance, oldest));
        }

        String currency = currencies.getFirst();
        Map<String, Money> byCategory = new LinkedHashMap<>();
        Money nonAdvertising = Money.zero(currency);
        Money advertising = Money.zero(currency);
        boolean settledOnly = true;
        for (FactQueryRepository.FeeGroupRow row : rows) {
            Money amount = money(row.amount(), currency);
            byCategory.put(row.feeCategory(), amount);
            if (ADVERTISING_CATEGORY.equals(row.feeCategory())) {
                advertising = advertising.plus(amount);
            } else {
                nonAdvertising = nonAdvertising.plus(amount);
            }
            settledOnly = settledOnly && row.settledOnly();
        }
        return new FeeTotals(nonAdvertising, advertising, byCategory, settledOnly,
                FactEvidence.of(provenance, oldest));
    }

    @Override
    @Transactional(readOnly = true)
    public AdvertisingTotals advertising(UUID platformListingVariantId, FactWindow window) {
        List<FactQueryRepository.AdvertisingGroupRow> rows = facts.advertising(
                platformListingVariantId, window.periodStart(), window.periodEnd());
        if (rows.isEmpty()) {
            return AdvertisingTotals.absent();
        }
        if (rows.size() > 1) {
            List<UUID> provenance = rows.stream()
                    .flatMap(row -> row.provenanceIds().stream()).distinct().toList();
            return new AdvertisingTotals(null, null, null, null, null,
                    FactEvidence.conflicted(provenance, oldest(rows.stream()
                            .map(FactQueryRepository.AdvertisingGroupRow::oldestSourceTime))));
        }
        FactQueryRepository.AdvertisingGroupRow row = rows.getFirst();
        return new AdvertisingTotals(
                money(row.spendAmount(), row.currencyCode()),
                row.impressions(), row.clicks(), row.attributedOrders(),
                money(row.attributedRevenue(), row.currencyCode()),
                FactEvidence.of(row.provenanceIds(), row.oldestSourceTime()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CostSnapshot> unitCost(UUID productVariantId, Instant asOf) {
        return facts.unitCost(productVariantId, asOf).map(row -> new CostSnapshot(
                row.id(), money(row.unitCost(), row.currencyCode()),
                row.effectiveFrom(), row.provenanceId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FinanceInputSnapshot> financeInput(UUID organizationId,
                                                       String inputCode,
                                                       UUID storeId,
                                                       UUID productVariantId,
                                                       Instant asOf) {
        return facts.financeInput(organizationId, inputCode, storeId, productVariantId, asOf)
                .map(row -> new FinanceInputSnapshot(
                        row.id(), row.inputCode(), row.rateValue(),
                        money(row.amountValue(), row.currencyCode()),
                        row.effectiveFrom(), row.provenanceId()));
    }

    @Override
    @Transactional(readOnly = true)
    public InternalStockSnapshot internalStock(UUID productVariantId, Instant asOf) {
        return facts.internalStock(productVariantId, asOf)
                .map(row -> new InternalStockSnapshot(
                        row.observedAt(), row.quantityOnHand(), row.quantityReserved(),
                        row.provenanceId()))
                .orElseGet(InternalStockSnapshot::absent);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listingVariantsWithActivity(UUID storeId, FactWindow window, int limit) {
        return facts.listingVariantsWithActivity(
                storeId, window.periodStart(), window.periodEnd(), Math.clamp(limit, 1, 5000));
    }

    private static FactEvidence conflicted(List<FactQueryRepository.MoneyGroupRow> rows) {
        return FactEvidence.conflicted(
                rows.stream().flatMap(row -> row.provenanceIds().stream()).distinct().toList(),
                oldest(rows.stream().map(FactQueryRepository.MoneyGroupRow::oldestSourceTime)));
    }

    private static Instant oldest(Stream<Instant> instants) {
        return instants.filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
    }

    private static Money money(BigDecimal amount, String currencyCode) {
        return amount == null || currencyCode == null ? null : Money.of(amount, currencyCode);
    }
}

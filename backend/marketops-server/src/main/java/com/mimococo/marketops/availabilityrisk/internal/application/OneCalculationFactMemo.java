package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.operatingfacts.AcceptedFactChange;
import com.mimococo.marketops.operatingfacts.AcceptedFactCursor;
import com.mimococo.marketops.operatingfacts.AdvertisingTotals;
import com.mimococo.marketops.operatingfacts.AvailabilityObservation;
import com.mimococo.marketops.operatingfacts.CostSnapshot;
import com.mimococo.marketops.operatingfacts.DailySaleTotal;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.FeeTotals;
import com.mimococo.marketops.operatingfacts.FinanceInputSnapshot;
import com.mimococo.marketops.operatingfacts.InternalStockSnapshot;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.PriceSnapshot;
import com.mimococo.marketops.operatingfacts.ReturnQualityEvidence;
import com.mimococo.marketops.operatingfacts.ReturnTotals;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.operatingfacts.SalesTotals;
import com.mimococo.marketops.operatingfacts.SellabilitySnapshot;
import com.mimococo.marketops.operatingfacts.StockSnapshot;
import com.mimococo.marketops.operatingfacts.TrafficTotals;
import com.mimococo.marketops.operatingfacts.WarehouseStockSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One calculation's answers, asked for once.
 *
 * <p>A single variant's risk is calculated from a channel view and a company
 * view of the same evidence, and for a variant sold through one listing in one
 * mode those two views ask the database several byte-identical questions: the
 * same completed sales over the same window, the same daily units, the same
 * availability timeline. The calculators are written to be pure and comparable,
 * so asking twice cannot produce a different answer — it only produces a second
 * round trip.
 *
 * <p>Correct because the reads it repeats are already guaranteed to agree.
 * Every one happens inside one read-only transaction at one fixed {@code asOf},
 * so PostgreSQL's snapshot makes the second execution return exactly the first
 * one's rows. That is the same guarantee the Contract's "a targeted result
 * equals a sweep result" property rests on, and remembering an answer inside one
 * calculation cannot weaken it.
 *
 * <p>Scoped to one calculation and thrown away with it. Nothing here is a cache
 * across variants, across transactions or across instants: a memo that outlived
 * its snapshot would be exactly the stale read this product refuses everywhere
 * else.
 */
final class OneCalculationFactMemo implements OperatingFactQuery {

    private final OperatingFactQuery delegate;
    private final Map<SalesKey,SalesTotals> sales = new HashMap<>();
    private final Map<WindowKey,List<DailySaleTotal>> dailyUnits = new HashMap<>();
    private final Map<ObservationKey,List<AvailabilityObservation>> observations = new HashMap<>();

    OneCalculationFactMemo(OperatingFactQuery delegate) {
        this.delegate = delegate;
    }

    private record SalesKey(UUID listingVariantId, SaleStage stage, Integer retentionWindowDays,
                            FactWindow window) {
    }

    private record WindowKey(UUID listingVariantId, FactWindow window) {
    }

    private record ObservationKey(UUID listingVariantId, String fulfillmentModeCode,
                                  FactWindow window) {
    }

    @Override
    public SalesTotals sales(UUID platformListingVariantId, SaleStage stage,
                             Integer retentionWindowDays, FactWindow window) {
        return sales.computeIfAbsent(
                new SalesKey(platformListingVariantId, stage, retentionWindowDays, window),
                key -> delegate.sales(key.listingVariantId(), key.stage(),
                        key.retentionWindowDays(), key.window()));
    }

    @Override
    public List<DailySaleTotal> dailyCompletedUnits(UUID platformListingVariantId,
                                                    FactWindow window) {
        return dailyUnits.computeIfAbsent(new WindowKey(platformListingVariantId, window),
                key -> delegate.dailyCompletedUnits(key.listingVariantId(), key.window()));
    }

    @Override
    public List<AvailabilityObservation> availabilityObservations(UUID platformListingVariantId,
                                                                  String fulfillmentModeCode,
                                                                  FactWindow window) {
        return observations.computeIfAbsent(
                new ObservationKey(platformListingVariantId, fulfillmentModeCode, window),
                key -> delegate.availabilityObservations(key.listingVariantId(),
                        key.fulfillmentModeCode(), key.window()));
    }

    // Everything below is asked at most once per calculation and is passed
    // straight through. Remembering an answer nobody asks for twice would add a
    // map lookup and hide nothing.

    @Override
    public Optional<PriceSnapshot> latestPrice(UUID platformListingVariantId, Instant asOf) {
        return delegate.latestPrice(platformListingVariantId, asOf);
    }

    @Override
    public StockSnapshot latestStock(UUID platformListingVariantId, Instant asOf) {
        return delegate.latestStock(platformListingVariantId, asOf);
    }

    @Override
    public TrafficTotals traffic(UUID platformListingVariantId, FactWindow window) {
        return delegate.traffic(platformListingVariantId, window);
    }

    @Override
    public ReturnTotals returns(UUID platformListingVariantId, FactWindow window) {
        return delegate.returns(platformListingVariantId, window);
    }

    @Override
    public ReturnQualityEvidence returnQualityEvidence(UUID platformListingVariantId,
                                                       FactWindow window,
                                                       Duration freshnessMaximum, Instant asOf) {
        return delegate.returnQualityEvidence(platformListingVariantId, window, freshnessMaximum,
                asOf);
    }

    @Override
    public FeeTotals fees(UUID platformListingVariantId, FactWindow window) {
        return delegate.fees(platformListingVariantId, window);
    }

    @Override
    public AdvertisingTotals advertising(UUID platformListingVariantId, FactWindow window) {
        return delegate.advertising(platformListingVariantId, window);
    }

    @Override
    public Optional<CostSnapshot> unitCost(UUID productVariantId, Instant asOf) {
        return delegate.unitCost(productVariantId, asOf);
    }

    @Override
    public Optional<FinanceInputSnapshot> financeInput(UUID organizationId, String inputCode,
                                                       UUID storeId, UUID productVariantId,
                                                       Instant asOf) {
        return delegate.financeInput(organizationId, inputCode, storeId, productVariantId, asOf);
    }

    @Override
    public InternalStockSnapshot internalStock(UUID productVariantId, Instant asOf) {
        return delegate.internalStock(productVariantId, asOf);
    }

    @Override
    public Optional<SellabilitySnapshot> latestSellability(UUID platformListingVariantId,
                                                           Instant asOf) {
        return delegate.latestSellability(platformListingVariantId, asOf);
    }

    @Override
    public List<WarehouseStockSnapshot> internalStockByWarehouse(UUID productVariantId,
                                                                 Instant asOf) {
        return delegate.internalStockByWarehouse(productVariantId, asOf);
    }

    @Override
    public List<AcceptedFactChange> factsAcceptedAfter(AcceptedFactCursor cursor, int limit) {
        return delegate.factsAcceptedAfter(cursor, limit);
    }

    @Override
    public List<UUID> listingVariantsWithActivity(UUID storeId, FactWindow window, int limit) {
        return delegate.listingVariantsWithActivity(storeId, window, limit);
    }
}

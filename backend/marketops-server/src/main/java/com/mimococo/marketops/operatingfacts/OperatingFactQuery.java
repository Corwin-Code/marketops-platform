package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Published read access to canonical operating facts.
 *
 * <p>The metric engine, the diagnosis rules and the guardrails all read through
 * this contract rather than through the fact tables, so the facts keep one owner
 * and every answer arrives with its own provenance and freshness attached.
 *
 * <p>Every method distinguishes absence from zero. A window in which nothing was
 * sold and a window for which no source published anything are different
 * business situations, and a contract that returned zero for both would make the
 * distinction impossible to recover downstream.
 */
public interface OperatingFactQuery {

    /** The most recent observed price of one listing variant at an instant. */
    Optional<PriceSnapshot> latestPrice(UUID platformListingVariantId, Instant asOf);

    /** The most recent observed availability, per fulfillment mode. */
    StockSnapshot latestStock(UUID platformListingVariantId, Instant asOf);

    /** Exposure and engagement over a window. */
    TrafficTotals traffic(UUID platformListingVariantId, FactWindow window);

    /**
     * Sales at one stage over a window.
     *
     * <p>A retained stage requires the window length it was retained for,
     * because "survived seven days" and "survived thirty days" are different
     * observations of the same order.
     */
    SalesTotals sales(UUID platformListingVariantId,
                      SaleStage stage,
                      Integer retentionWindowDays,
                      FactWindow window);

    /** Returns over a window, with their reason mix. */
    ReturnTotals returns(UUID platformListingVariantId, FactWindow window);

    /** Platform charges over a window. */
    FeeTotals fees(UUID platformListingVariantId, FactWindow window);

    /** Advertising cost and effect over a window. */
    AdvertisingTotals advertising(UUID platformListingVariantId, FactWindow window);

    /** The purchase cost in force for one internal variant at an instant. */
    Optional<CostSnapshot> unitCost(UUID productVariantId, Instant asOf);

    /**
     * The finance input in force at an instant, resolved most specific first.
     *
     * <p>A variant-scoped version wins over a store-scoped one, which wins over
     * an organization-scoped one. Resolution order is part of the contract
     * because a profit figure has to be able to say which version it used.
     */
    Optional<FinanceInputSnapshot> financeInput(UUID organizationId,
                                                String inputCode,
                                                UUID storeId,
                                                UUID productVariantId,
                                                Instant asOf);

    /** What the company itself holds of one internal variant. */
    InternalStockSnapshot internalStock(UUID productVariantId, Instant asOf);

    /**
     * Listing variants on one store that any source reported activity for
     * inside a window.
     *
     * <p>This is the subject list a metric run works through. Deriving it from
     * facts rather than from the catalogue keeps a run proportional to what
     * actually happened instead of to how many listings exist.
     */
    List<UUID> listingVariantsWithActivity(UUID storeId, FactWindow window, int limit);
}

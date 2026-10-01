package com.mimococo.marketops.operatingfacts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One listing variant's record over a window, for comparing the period before an action with the
 * period after it (P10, 2026-10-01).
 *
 * <p>Everything a source stated inside the window, oldest first: the units ordered on each UTC day the
 * listing has a record for, every price snapshot, every stock snapshot summed over fulfillment modes
 * and every sellability statement. A day without a record is absent, never zero: whether the store's
 * order facts covered that day is a separate question ({@link OperatingFactQuery#storeOrderDays}).
 *
 * @param orders units ordered per UTC day the listing has a record for
 * @param prices the price snapshots
 * @param stock units available per stock snapshot, summed over fulfillment modes
 * @param sellability whether the listing could be bought, per statement
 */
public record ListingWindowRecord(List<DayOrders> orders, List<PricePoint> prices, List<StockPoint> stock,
                                  List<SellablePoint> sellability) {

    public ListingWindowRecord {
        orders = List.copyOf(orders);
        prices = List.copyOf(prices);
        stock = List.copyOf(stock);
        sellability = List.copyOf(sellability);
    }

    /**
     * Units ordered on one UTC day.
     *
     * @param day the day
     * @param orderedUnits units ordered that day
     */
    public record DayOrders(LocalDate day, long orderedUnits) {
    }

    /**
     * One price snapshot as a buyer saw it.
     *
     * @param observedAt when the marketplace considered it true
     * @param currencyCode the currency of the amounts
     * @param buyerPrice the promotion price when there is one, the selling price otherwise, else the
     *        list price; {@code null} when none was stated
     * @param sellerPromotion whether the price with the seller's promotions differed from the price
     *        without them, the same test the price write gate applies
     * @param priceIndexNative the marketplace's own price index class, or {@code null}
     */
    public record PricePoint(Instant observedAt, String currencyCode, BigDecimal buyerPrice,
                             boolean sellerPromotion, String priceIndexNative) {
    }

    /**
     * Units available in one stock snapshot.
     *
     * @param observedAt when the source considered it true
     * @param availableUnits units available summed over the fulfillment modes it reported, or
     *        {@code null} when it reported none
     */
    public record StockPoint(Instant observedAt, Integer availableUnits) {
    }

    /**
     * One statement of whether the listing could be bought.
     *
     * @param observedAt when the source considered it true
     * @param sellable {@code YES}, {@code NO} or {@code UNKNOWN} as the source stated it
     */
    public record SellablePoint(Instant observedAt, String sellable) {
    }
}

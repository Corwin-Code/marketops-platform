package com.mimococo.marketops.operatingfacts;

/**
 * A store's daily order facts over a window, summed across its listings (P10).
 *
 * @param daysCovered UTC days of the window on which any listing of the store has a daily order record
 * @param orderedUnits units ordered across the store over those days, or {@code null} when none was covered
 * @param listingsWithOrders listings with at least one unit ordered, or {@code null} when none was covered
 */
public record StoreOrderTotals(int daysCovered, Long orderedUnits, Integer listingsWithOrders) {
}

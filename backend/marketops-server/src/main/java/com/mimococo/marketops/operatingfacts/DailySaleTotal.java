package com.mimococo.marketops.operatingfacts;

import java.time.LocalDate;

/**
 * Completed units on one day, in the window's own time zone.
 *
 * <p>A window total cannot answer whether one unusual day produced it. Thirty
 * units in a week is a steady five a day or a single bulk order and six empty
 * days, and those are different demand signals with different consequences for
 * a replenishment decision.
 *
 * @param day the day the units were completed on, in UTC
 * @param completedUnits units completed that day
 */
public record DailySaleTotal(LocalDate day, long completedUnits) {
}

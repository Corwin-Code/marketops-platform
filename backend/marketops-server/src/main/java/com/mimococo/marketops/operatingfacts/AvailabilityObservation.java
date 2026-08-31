package com.mimococo.marketops.operatingfacts;

import java.time.Instant;

/**
 * One point in the record of whether a listing variant could sell.
 *
 * <p>A demand window can only be read honestly against this timeline. Four units
 * sold in a week means something different when the listing was buyable all
 * week and when it was buyable for a day, and only the sequence of observations
 * distinguishes the two.
 *
 * @param observedAt when the source considered it true
 * @param availableUnits units the source reported, or {@code null} when it reported none
 * @param sellable {@code YES}, {@code NO} or {@code UNKNOWN} as the source stated it
 */
public record AvailabilityObservation(Instant observedAt, Integer availableUnits, String sellable) {

    /** Whether this observation describes a moment the listing could actually sell. */
    public boolean saleable() {
        return "YES".equals(sellable) && availableUnits != null && availableUnits > 0;
    }
}

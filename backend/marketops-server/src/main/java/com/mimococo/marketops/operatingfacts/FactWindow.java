package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.Objects;

/**
 * A closed period a fact query is asked about.
 *
 * <p>The interval is half-open: a fact at the start instant is inside the window
 * and one at the end instant is not. Two adjacent windows therefore partition
 * time without counting a boundary fact twice, which is what makes a seven-day
 * and a thirty-day answer comparable.
 *
 * @param periodStart first instant inside the window
 * @param periodEnd first instant after the window
 */
public record FactWindow(Instant periodStart, Instant periodEnd) {

    public FactWindow {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (!periodStart.isBefore(periodEnd)) {
            throw new IllegalArgumentException("a window must start before it ends");
        }
    }

    /** The window of the given length ending at an instant. */
    public static FactWindow endingAt(Instant end, java.time.Duration length) {
        return new FactWindow(end.minus(length), end);
    }
}

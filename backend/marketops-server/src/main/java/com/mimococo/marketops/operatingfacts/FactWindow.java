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

    /** The window of the given length ending at an exact instant. */
    public static FactWindow endingAt(Instant end, java.time.Duration length) {
        return new FactWindow(end.minus(length), end);
    }

    /**
     * The window of the given length ending at the start of the current hour.
     *
     * <p>A calculation's reproducibility digest includes the window it covered,
     * so a window that ends at "right now" makes every recomputation a
     * different question and every answer a new row. Two runs a second apart
     * would each write a full set of values that differ only in the instant
     * somebody asked, and the history of a figure would fill with noise the
     * moment a scheduler existed.
     *
     * <p>Aligning to the hour is the smallest granularity at which "recompute
     * this" is the same question twice. It also means a late fact that arrives
     * within the hour produces a genuinely new value rather than a duplicate,
     * because the fact set — not the clock — is what differs.
     */
    public static FactWindow alignedEndingAt(Instant end, java.time.Duration length) {
        Instant boundary = end.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        return new FactWindow(boundary.minus(length), boundary);
    }
}

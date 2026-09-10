package com.mimococo.marketops.listingconversion.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The version-attributed window and the days excluded from it.
 *
 * <p>A day in which two descriptions were both displayed is excluded from the
 * version-attributed window without prorating. Its cost and protection
 * responsibilities remain in the whole-window protection view; only the
 * attribution of the primary metric leaves it out.
 */
public final class VersionWindow {

    private VersionWindow() {
    }

    /** A display observation: which version was displayed, and when. */
    public record Display(String textDigest, Instant observedAt) {
        public Display {
            Objects.requireNonNull(textDigest, "textDigest");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    /** The window and the transition days excluded from it. */
    public record Attribution(Instant windowStart, Instant windowEnd, List<LocalDate> excludedDays, ZoneId timezone) {
        public Attribution {
            excludedDays = List.copyOf(excludedDays);
        }
    }

    /**
     * Every source-calendar day with a change between evidenced versions.
     *
     * @param displays what was displayed, in any order
     * @param windowStart the start of the whole window
     * @param windowEnd the end of the whole window
     */
    public static Attribution attribute(List<Display> displays, Instant windowStart, Instant windowEnd) {
        return attribute(displays, windowStart, windowEnd, ZoneOffset.UTC);
    }

    public static Attribution attribute(List<Display> displays, Instant windowStart, Instant windowEnd, ZoneId timezone) {
        Objects.requireNonNull(timezone, "timezone");
        TreeSet<LocalDate> excluded = new TreeSet<>();
        List<Display> ordered = displays.stream()
                .sorted(java.util.Comparator.comparing(Display::observedAt).thenComparing(Display::textDigest)).toList();
        Display prior = null;
        for (Display display : ordered) {
            if (!display.observedAt().isBefore(windowEnd)) break;
            if (prior != null && !display.textDigest().equals(prior.textDigest())
                    && !display.observedAt().isBefore(windowStart)) {
                // Carry the last known version across midnight and the requested
                // boundary. A new observation of another version marks a whole
                // transition day; it is not an exact activation timestamp.
                excluded.add(display.observedAt().atZone(timezone).toLocalDate());
            }
            prior = display;
        }
        return new Attribution(windowStart, windowEnd, new ArrayList<>(excluded), timezone);
    }

    /** Whether one instant falls on an excluded day. */
    public static boolean excluded(Attribution attribution, Instant at) {
        LocalDate day = at.atZone(attribution.timezone()).toLocalDate();
        return attribution.excludedDays().contains(day);
    }
}

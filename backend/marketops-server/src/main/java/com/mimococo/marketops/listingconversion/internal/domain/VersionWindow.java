package com.mimococo.marketops.listingconversion.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    public record Attribution(Instant windowStart, Instant windowEnd, List<LocalDate> excludedDays) {
        public Attribution {
            excludedDays = List.copyOf(excludedDays);
        }
    }

    /**
     * Every calendar day (UTC) on which more than one version was displayed.
     *
     * @param displays what was displayed, in any order
     * @param windowStart the start of the whole window
     * @param windowEnd the end of the whole window
     */
    public static Attribution attribute(List<Display> displays, Instant windowStart, Instant windowEnd) {
        TreeSet<LocalDate> excluded = new TreeSet<>();
        java.util.Map<LocalDate, java.util.Set<String>> versionsPerDay = new java.util.HashMap<>();
        for (Display display : displays) {
            if (display.observedAt().isBefore(windowStart) || !display.observedAt().isBefore(windowEnd)) {
                continue;
            }
            LocalDate day = display.observedAt().atZone(ZoneOffset.UTC).toLocalDate();
            versionsPerDay.computeIfAbsent(day, ignored -> new java.util.HashSet<>()).add(display.textDigest());
        }
        versionsPerDay.forEach((day, versions) -> {
            if (versions.size() > 1) {
                excluded.add(day);
            }
        });
        return new Attribution(windowStart, windowEnd, new ArrayList<>(excluded));
    }

    /** Whether one instant falls on an excluded day. */
    public static boolean excluded(Attribution attribution, Instant at) {
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        return attribution.excludedDays().contains(day);
    }
}

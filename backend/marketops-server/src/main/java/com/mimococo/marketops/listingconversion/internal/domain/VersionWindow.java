package com.mimococo.marketops.listingconversion.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public record Display(String textDigest, Instant observedAt, boolean qualified) {
        public Display(String textDigest, Instant observedAt) {
            this(textDigest, observedAt, true);
        }

        public Display {
            if (qualified) Objects.requireNonNull(textDigest, "textDigest");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    /** The window and the transition days excluded from it. */
    public record Attribution(Instant windowStart, Instant windowEnd, List<LocalDate> excludedDays, ZoneId timezone,
                              Map<LocalDate,String> includedDayDigests, List<LocalDate> uncoveredDays) {
        public Attribution {
            excludedDays = List.copyOf(excludedDays);
            includedDayDigests = Map.copyOf(includedDayDigests);
            uncoveredDays = List.copyOf(uncoveredDays);
        }

        /** Exact target coverage after whole transition days have been excluded. */
        public boolean fullyCovers(String expectedDigest) {
            return expectedDigest != null && !expectedDigest.isBlank() && uncoveredDays.isEmpty()
                    && !includedDayDigests.isEmpty()
                    && includedDayDigests.values().stream().allMatch(expectedDigest::equals);
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
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowStart.isBefore(windowEnd)) throw new IllegalArgumentException("windowStart must precede windowEnd");
        TreeSet<LocalDate> excluded = new TreeSet<>();
        TreeSet<LocalDate> uncovered = new TreeSet<>();
        Map<LocalDate,String> included = new LinkedHashMap<>();
        List<Display> ordered = displays.stream()
                .filter(display -> display.observedAt().isBefore(windowEnd))
                .sorted(java.util.Comparator.comparing(Display::observedAt)
                        .thenComparing(display -> display.textDigest() == null ? "" : display.textDigest()))
                .toList();
        LocalDate first = windowStart.atZone(timezone).toLocalDate();
        LocalDate last = windowEnd.minusNanos(1).atZone(timezone).toLocalDate();
        Display state = null;
        int cursor = 0;
        while (cursor < ordered.size() && ordered.get(cursor).observedAt().isBefore(windowStart)) {
            ObservationBatch batch=batch(ordered,cursor);
            state=batch.state();
            cursor=batch.nextIndex();
        }
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            Instant segmentStart = day.atStartOfDay(timezone).toInstant();
            if (segmentStart.isBefore(windowStart)) segmentStart = windowStart;
            Instant segmentEnd = day.plusDays(1).atStartOfDay(timezone).toInstant();
            if (segmentEnd.isAfter(windowEnd)) segmentEnd = windowEnd;
            while (cursor < ordered.size() && ordered.get(cursor).observedAt().isBefore(segmentStart)) {
                ObservationBatch batch=batch(ordered,cursor);
                state=batch.state();
                cursor=batch.nextIndex();
            }
            Display startState = state;
            TreeSet<String> qualifiedDigests = new TreeSet<>();
            boolean unknown = startState == null || !startState.qualified();
            if (startState != null && startState.qualified()) qualifiedDigests.add(startState.textDigest());
            while (cursor < ordered.size() && ordered.get(cursor).observedAt().isBefore(segmentEnd)) {
                ObservationBatch batch=batch(ordered,cursor);
                qualifiedDigests.addAll(batch.qualifiedDigests());
                unknown |= batch.ambiguous();
                state=batch.state();
                cursor=batch.nextIndex();
            }
            if (qualifiedDigests.size() > 1) {
                // Mixed source-day observations cannot be split into hours.
                // The whole day leaves the effect cohort.
                excluded.add(day);
            } else if (unknown || qualifiedDigests.isEmpty()) {
                uncovered.add(day);
            } else {
                included.put(day, qualifiedDigests.first());
            }
        }
        return new Attribution(windowStart, windowEnd, new ArrayList<>(excluded), timezone,
                included, new ArrayList<>(uncovered));
    }

    private record ObservationBatch(Display state, TreeSet<String> qualifiedDigests,
                                    boolean ambiguous, int nextIndex) { }

    /** Same-time conflicting or unknown reports cannot choose a display state by row order. */
    private static ObservationBatch batch(List<Display> ordered,int start) {
        Instant observedAt=ordered.get(start).observedAt();
        TreeSet<String> digests=new TreeSet<>();
        boolean unknown=false;
        int cursor=start;
        while (cursor<ordered.size() && ordered.get(cursor).observedAt().equals(observedAt)) {
            Display display=ordered.get(cursor++);
            if (display.qualified()) digests.add(display.textDigest()); else unknown=true;
        }
        boolean ambiguous=unknown || digests.size()!=1;
        Display state=ambiguous?new Display(null,observedAt,false):new Display(digests.first(),observedAt,true);
        return new ObservationBatch(state,digests,ambiguous,cursor);
    }

    /** Whether one instant falls on an excluded day. */
    public static boolean excluded(Attribution attribution, Instant at) {
        LocalDate day = at.atZone(attribution.timezone()).toLocalDate();
        return attribution.excludedDays().contains(day);
    }
}

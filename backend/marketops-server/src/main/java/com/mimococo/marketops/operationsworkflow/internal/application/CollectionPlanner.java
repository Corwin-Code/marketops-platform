package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.marketplaceintegration.ScheduledAcquisition.RunRecord;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * When each scheduled dataset is due and which window a due run reads, per the Owner's decision of
 * 2026-09-29: snapshots once a day, ordered units once a day, search demand once a week.
 *
 * <p>Every UTC day has one collection slot at the configured time. What a slot asks for:
 * <ul>
 *   <li>a snapshot (catalogue, prices, stock, status, content rating): one successful run after the
 *       slot started;</li>
 *   <li>ordered units: every whole UTC day from 8 to 2 days before the slot's date that no run has
 *       read yet, oldest first. Facts are first-write-wins, so a day is read only once Ozon has had a
 *       full day to finish it, and days missed while the platform was down are caught up;</li>
 *   <li>search demand: the last Monday-to-Sunday week that ended at least 2 days before the slot's
 *       date (Ozon computes a day within 1-2 days), so a new week is read on Wednesdays.</li>
 * </ul>
 * A run of any kind that succeeded satisfies a target, so a manual collection counts too.
 */
final class CollectionPlanner {

    /** How often a dataset is collected and whether its runs read a window. */
    enum Cadence {
        DAILY_SNAPSHOT,
        DAILY_WINDOW,
        WEEKLY_WINDOW
    }

    /**
     * The datasets collected on a schedule, in the order a pass works through them: the catalogue
     * first, because the other datasets resolve their records through its listings.
     */
    static final Map<String, Cadence> CADENCES;

    static {
        Map<String, Cadence> cadences = new LinkedHashMap<>();
        cadences.put("LISTING", Cadence.DAILY_SNAPSHOT);
        cadences.put("PRICE", Cadence.DAILY_SNAPSHOT);
        cadences.put("STOCK", Cadence.DAILY_SNAPSHOT);
        cadences.put("LISTING_HEALTH", Cadence.DAILY_SNAPSHOT);
        cadences.put("LISTING_CONTENT", Cadence.DAILY_SNAPSHOT);
        // The promotion snapshot first: the candidates and participants are asked about the
        // promotions it names, one request each (P7).
        cadences.put("PROMOTION", Cadence.DAILY_SNAPSHOT);
        cadences.put("PROMOTION_CANDIDATE", Cadence.DAILY_SNAPSHOT);
        cadences.put("PROMOTION_PARTICIPANT", Cadence.DAILY_SNAPSHOT);
        // The store's own standing: its ratings and its warehouses.
        cadences.put("SELLER_RATING", Cadence.DAILY_SNAPSHOT);
        cadences.put("FBS_WAREHOUSE", Cadence.DAILY_SNAPSHOT);
        // Buyers' discount requests: the whole list, every state, with every snapshot (P8).
        cadences.put("DISCOUNT_REQUEST", Cadence.DAILY_SNAPSHOT);
        cadences.put("TRAFFIC", Cadence.DAILY_WINDOW);
        cadences.put("LISTING_SEARCH", Cadence.WEEKLY_WINDOW);
        cadences.put("LISTING_SEARCH_TERM", Cadence.WEEKLY_WINDOW);
        CADENCES = java.util.Collections.unmodifiableMap(cadences);
    }

    /** A day is read once this many dates have passed since it: it ended a full day earlier. */
    static final int DAY_LAG_DAYS = 2;

    /** How many days of ordered units a slot looks back over, to catch up missed days. */
    static final int DAY_CATCH_UP_DAYS = 7;

    /** A week is read once its end is this many days behind the slot's date. */
    static final int WEEK_LAG_DAYS = 2;

    /** Scheduled runs made for one target before the scheduler leaves it to a person. */
    static final int MAX_ATTEMPTS = 3;

    /** How long after a failed run the scheduler waits before trying the same target again. */
    static final Duration FAILURE_BACKOFF = Duration.ofMinutes(30);

    /** How far back a job's runs are read to decide what is due. */
    static final Duration HISTORY = Duration.ofDays(21);

    /** How many slots ahead the next target is looked for. */
    private static final int LOOK_AHEAD_SLOTS = 8;

    private CollectionPlanner() {
    }

    /**
     * One thing a slot asks for.
     *
     * @param key names the target, e.g. {@code day:2026-09-27}; stable across passes
     * @param windowFrom start of the window a run reads, or {@code null} for a snapshot
     * @param windowTo end of the window a run reads, or {@code null} for a snapshot
     * @param slotStart when the slot asking for it started
     */
    record Target(String key, Instant windowFrom, Instant windowTo, Instant slotStart) {

        boolean windowed() {
            return windowFrom != null;
        }
    }

    /** The start of the slot a moment falls in: today's once it has passed, otherwise yesterday's. */
    static Instant slotStart(Instant now, LocalTime dailyAt) {
        Instant today = LocalDate.ofInstant(now, ZoneOffset.UTC).atTime(dailyAt).toInstant(ZoneOffset.UTC);
        return now.isBefore(today) ? today.minus(Duration.ofDays(1)) : today;
    }

    /** What the slot starting at {@code slot} asks for, oldest first. */
    static List<Target> targets(Cadence cadence, Instant slot) {
        LocalDate date = LocalDate.ofInstant(slot, ZoneOffset.UTC);
        return switch (cadence) {
            case DAILY_SNAPSHOT -> List.of(new Target("snapshot:" + date, null, null, slot));
            case DAILY_WINDOW -> {
                List<Target> days = new ArrayList<>();
                LocalDate last = date.minusDays(DAY_LAG_DAYS);
                for (int back = DAY_CATCH_UP_DAYS - 1; back >= 0; back--) {
                    LocalDate day = last.minusDays(back);
                    days.add(new Target("day:" + day, start(day), start(day.plusDays(1)), slot));
                }
                yield List.copyOf(days);
            }
            case WEEKLY_WINDOW -> {
                // The Monday that ends the week: the latest one at least WEEK_LAG_DAYS behind the slot.
                LocalDate end = date.minusDays(WEEK_LAG_DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate begin = end.minusDays(7);
                yield List.of(new Target("week:" + begin, start(begin), start(end), slot));
            }
        };
    }

    /** Whether a run was made for the target: the same window, or for a snapshot, in its slot. */
    static boolean matches(Target target, RunRecord run) {
        return target.windowed()
                ? run.covers(target.windowFrom(), target.windowTo())
                : run.windowFrom() == null && !run.createdAt().isBefore(target.slotStart());
    }

    /** Whether any run, scheduled or manual, already read the target. */
    static boolean satisfied(Target target, List<RunRecord> runs) {
        return runs.stream().anyMatch(run -> run.succeeded() && matches(target, run));
    }

    /** The first target of the current slot that no run has read yet. */
    static Optional<Target> due(Cadence cadence, Instant now, LocalTime dailyAt, List<RunRecord> runs) {
        return firstOpen(targets(cadence, slotStart(now, dailyAt)), runs);
    }

    /** The first target after the current slot that no run has read yet. */
    static Optional<Target> upcoming(Cadence cadence, Instant now, LocalTime dailyAt, List<RunRecord> runs) {
        Instant slot = slotStart(now, dailyAt);
        for (int ahead = 1; ahead <= LOOK_AHEAD_SLOTS; ahead++) {
            Optional<Target> open = firstOpen(targets(cadence, slot.plus(Duration.ofDays(ahead))), runs);
            if (open.isPresent()) {
                return open;
            }
        }
        return Optional.empty();
    }

    /** The scheduled runs made for a target, newest first when {@code runs} are. */
    static List<RunRecord> attempts(Target target, List<RunRecord> runs) {
        return runs.stream().filter(run -> "SCHEDULED".equals(run.runKind()) && matches(target, run)).toList();
    }

    private static Optional<Target> firstOpen(List<Target> targets, List<RunRecord> runs) {
        return targets.stream().filter(target -> !satisfied(target, runs)).findFirst();
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}

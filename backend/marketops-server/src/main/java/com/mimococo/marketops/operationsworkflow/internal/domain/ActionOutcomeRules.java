package com.mimococo.marketops.operationsworkflow.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How an executed action is judged against the period before it (P10, Owner decisions 2026-10-01).
 *
 * <p>Pure: given what each window held, the verdict is fixed. The rules, version 1:
 * <ul>
 *   <li>the windows are calendar days in UTC around the day of the action, which belongs to neither;
 *       a window is readable when the store's daily order facts cover at least 70 % of its days;</li>
 *   <li>a stock-out, a seller promotion, a price that did not hold or another action on the listing
 *       inside the windows makes the verdict {@code INDETERMINATE}, each with its reason;</li>
 *   <li>otherwise units ordered decide it: orders appearing where there were none, or the daily rate
 *       moving up by 20 % or more, is {@code IMPROVED}; orders stopping, or the rate falling by 20 % or
 *       more, is {@code REGRESSED}; anything else, including no order on either side, {@code UNCHANGED};</li>
 *   <li>the leading signals stand beside the verdict whatever it is: search users moving by 20 % or
 *       more and Ozon's price index class changing, each up or down.</li>
 * </ul>
 */
public final class ActionOutcomeRules {

    /** The version every reading records, raised whenever a rule here changes. */
    public static final int RULE_VERSION = 1;

    /** The relative move that counts as a change, both for orders and for search users. */
    public static final BigDecimal MOVE = new BigDecimal("0.20");

    /** Below this many units on both sides together, a verdict on orders notes its small sample. */
    public static final long SMALL_SAMPLE_UNITS = 10;

    /** Ozon's price index classes from worst to best; anything else is no class. */
    private static final Map<String, Integer> INDEX_RANK = Map.of("RED", 1, "YELLOW", 2, "GREEN", 3);

    private ActionOutcomeRules() {
    }

    /** The two comparisons: seven days against seven, then fourteen against fourteen. */
    public enum Stage {
        PRELIMINARY(7),
        FINAL(14);

        private final int days;

        Stage(int days) {
            this.days = days;
        }

        /** How many days each window of this stage holds. */
        public int days() {
            return days;
        }
    }

    /**
     * The two windows of one stage around the day of an action, inclusive at both ends.
     *
     * @param baselineFrom first day before the action
     * @param baselineTo the day before the action
     * @param observationFrom the day after the action
     * @param observationTo last day after the action
     */
    public record Windows(LocalDate baselineFrom, LocalDate baselineTo,
                          LocalDate observationFrom, LocalDate observationTo) {

        /** The windows of a stage around the day of an action. */
        public static Windows around(LocalDate actedOn, Stage stage) {
            return new Windows(actedOn.minusDays(stage.days()), actedOn.minusDays(1),
                    actedOn.plusDays(1), actedOn.plusDays(stage.days()));
        }
    }

    /**
     * What one window held.
     *
     * @param daysCovered days of the window the store's daily order facts cover
     * @param orderedUnits units the listing ordered over the covered days, {@code null} when none was covered
     * @param searchUsers search users of the newest search period wholly inside the window, or {@code null}
     * @param priceIndex the newest price index class stated inside the window, or {@code null}
     */
    public record Side(int daysCovered, Long orderedUnits, Long searchUsers, String priceIndex) {
    }

    /**
     * What happened inside the windows besides the orders.
     *
     * @param stockout a stock snapshot without units, or a statement that the listing could not be
     *        bought, inside the window after the action
     * @param promotion a seller promotion inside the window after the action, when that is not the
     *        action itself
     * @param priceHeld whether every buyer price after the action was the price the action set;
     *        {@code null} for an action that set no price, or when no price was observed
     * @param otherAction another followed action on the listing inside either window
     */
    public record During(boolean stockout, boolean promotion, Boolean priceHeld, boolean otherAction) {
    }

    /**
     * A judgement.
     *
     * @param verdict IMPROVED, UNCHANGED, REGRESSED or INDETERMINATE
     * @param leadingSignal POSITIVE, NEGATIVE, MIXED, NONE or UNAVAILABLE
     * @param reasons why, in a fixed vocabulary
     */
    public record Judgement(String verdict, String leadingSignal, List<String> reasons) {

        public Judgement {
            reasons = List.copyOf(reasons);
        }
    }

    /** How many covered days a window of this length needs to be read: 70 %, rounded up. */
    public static int requiredDays(int windowDays) {
        return (windowDays * 7 + 9) / 10;
    }

    /** Judge one stage from what its two windows held. */
    public static Judgement judge(Stage stage, Side before, Side after, During during) {
        List<String> reasons = new ArrayList<>();
        String signal = leadingSignal(before, after);
        int required = requiredDays(stage.days());
        if (before.daysCovered() < required || after.daysCovered() < required
                || before.orderedUnits() == null || after.orderedUnits() == null) {
            reasons.add("INSUFFICIENT_COVERAGE");
        }
        if (during.stockout()) {
            reasons.add("STOCKOUT_IN_WINDOW");
        }
        if (during.promotion()) {
            reasons.add("PROMOTION_IN_WINDOW");
        }
        if (Boolean.FALSE.equals(during.priceHeld())) {
            reasons.add("PRICE_NOT_HELD");
        }
        if (during.otherAction()) {
            reasons.add("OTHER_ACTION_IN_WINDOW");
        }
        if (!reasons.isEmpty()) {
            return new Judgement("INDETERMINATE", signal, reasons);
        }
        long unitsBefore = before.orderedUnits();
        long unitsAfter = after.orderedUnits();
        String verdict;
        if (unitsBefore == 0 && unitsAfter == 0) {
            verdict = "UNCHANGED";
            reasons.add("NO_ORDERS_EITHER_SIDE");
        } else if (unitsBefore == 0) {
            verdict = "IMPROVED";
            reasons.add("ORDERS_APPEARED");
        } else if (unitsAfter == 0) {
            verdict = "REGRESSED";
            reasons.add("ORDERS_STOPPED");
        } else {
            BigDecimal rateBefore = BigDecimal.valueOf(unitsBefore)
                    .divide(BigDecimal.valueOf(before.daysCovered()), 8, RoundingMode.HALF_UP);
            BigDecimal rateAfter = BigDecimal.valueOf(unitsAfter)
                    .divide(BigDecimal.valueOf(after.daysCovered()), 8, RoundingMode.HALF_UP);
            BigDecimal change = rateAfter.divide(rateBefore, 8, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
            if (change.compareTo(MOVE) >= 0) {
                verdict = "IMPROVED";
                reasons.add("ORDER_RATE_UP");
            } else if (change.compareTo(MOVE.negate()) <= 0) {
                verdict = "REGRESSED";
                reasons.add("ORDER_RATE_DOWN");
            } else {
                verdict = "UNCHANGED";
                reasons.add("ORDER_RATE_STEADY");
            }
            if (unitsBefore + unitsAfter < SMALL_SAMPLE_UNITS) {
                reasons.add("SMALL_SAMPLE");
            }
        }
        return new Judgement(verdict, signal, reasons);
    }

    /**
     * The leading signals together: POSITIVE when at least one moved up and none down, NEGATIVE the
     * other way, MIXED when they disagree, NONE when they could be read and did not move, UNAVAILABLE
     * when neither could be read.
     */
    static String leadingSignal(Side before, Side after) {
        int measured = 0;
        int up = 0;
        int down = 0;
        if (before.searchUsers() != null && after.searchUsers() != null) {
            measured++;
            long was = before.searchUsers();
            long is = after.searchUsers();
            if (was == 0) {
                up += is > 0 ? 1 : 0;
            } else {
                BigDecimal change = BigDecimal.valueOf(is)
                        .divide(BigDecimal.valueOf(was), 8, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
                up += change.compareTo(MOVE) >= 0 ? 1 : 0;
                down += change.compareTo(MOVE.negate()) <= 0 ? 1 : 0;
            }
        }
        Integer wasRank = before.priceIndex() == null ? null : INDEX_RANK.get(before.priceIndex());
        Integer isRank = after.priceIndex() == null ? null : INDEX_RANK.get(after.priceIndex());
        if (wasRank != null && isRank != null) {
            measured++;
            up += isRank > wasRank ? 1 : 0;
            down += isRank < wasRank ? 1 : 0;
        }
        if (measured == 0) {
            return "UNAVAILABLE";
        }
        if (up > 0 && down > 0) {
            return "MIXED";
        }
        if (up > 0) {
            return "POSITIVE";
        }
        return down > 0 ? "NEGATIVE" : "NONE";
    }
}

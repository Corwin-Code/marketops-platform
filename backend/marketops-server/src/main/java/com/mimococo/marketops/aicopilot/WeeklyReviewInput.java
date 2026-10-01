package com.mimococo.marketops.aicopilot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a weekly review is asked about (P10, Owner decisions 2026-10-01): a store's week as the
 * workflow keeps it, with the followed actions and what the platform computed of them.
 *
 * <p>Every figure here is the platform's own: units counted, ratios and verdicts its rules gave.
 * Nothing is a cost or a profit amount, and a model may only quote these figures, never treat them as
 * citable facts: they have no metric value behind them.
 *
 * @param weekStart the Monday (UTC) of the week reviewed
 * @param weekEnd its Sunday
 * @param weekComplete whether the week had ended when it was reviewed; a review asked for during the
 *        week is provisional
 * @param ordersFrom first of the newest seven days of daily order facts, or {@code null} when none
 * @param ordersTo last of them
 * @param ordersDaysCovered how many of those days the store's order facts cover
 * @param orderedUnits units ordered across the store over them, or {@code null} when none was covered
 * @param listingsWithOrders products with at least one order over them, or {@code null}
 * @param actionsActed followed actions that took effect during the week
 * @param readingsRecorded readings recorded during the week
 * @param actionsObserving followed actions still waiting for their final reading
 * @param improvedCount final verdicts IMPROVED so far
 * @param unchangedCount final verdicts UNCHANGED so far
 * @param regressedCount final verdicts REGRESSED so far
 * @param indeterminateCount final verdicts INDETERMINATE so far
 * @param actions the actions reviewed, most telling first
 */
public record WeeklyReviewInput(LocalDate weekStart, LocalDate weekEnd, boolean weekComplete,
                                LocalDate ordersFrom, LocalDate ordersTo,
                                int ordersDaysCovered, Long orderedUnits, Integer listingsWithOrders,
                                int actionsActed, int readingsRecorded, int actionsObserving, int improvedCount,
                                int unchangedCount, int regressedCount, int indeterminateCount,
                                List<Action> actions) {

    public WeeklyReviewInput {
        actions = List.copyOf(actions);
    }

    /**
     * One followed action and its newest reading.
     *
     * @param actionRef the followed action
     * @param listingVariantId the listing it changed
     * @param actionKind PRICE_CHANGE, PROMOTION_JOINED or PROMOTION_LEFT
     * @param source PRICE_COMMAND, PRICE_DECISION or PROMOTION_DECISION
     * @param actedOn the UTC day it took effect
     * @param priceChangeRate the price change as a signed ratio, or {@code null}
     * @param stage NONE, PRELIMINARY or FINAL: the newest reading
     * @param verdict the newest reading's verdict, or OBSERVING before any
     * @param leadingSignal the newest reading's leading signal, or {@code null}
     * @param reasons the newest reading's reasons
     * @param ordersBefore units ordered in the window before, or {@code null}
     * @param ordersAfter units ordered in the window after, or {@code null}
     * @param daysBefore covered days of the window before, or {@code null}
     * @param daysAfter covered days of the window after, or {@code null}
     * @param searchChangeRate search users after against before as a signed ratio, or {@code null}
     * @param priceIndexBefore the price index class before, or {@code null}
     * @param priceIndexAfter the price index class after, or {@code null}
     * @param preliminaryDueOn when the preliminary reading is expected
     * @param finalDueOn when the final reading is expected
     */
    public record Action(UUID actionRef, UUID listingVariantId, String actionKind, String source,
                         LocalDate actedOn, BigDecimal priceChangeRate, String stage, String verdict,
                         String leadingSignal, List<String> reasons, Long ordersBefore, Long ordersAfter,
                         Integer daysBefore, Integer daysAfter, BigDecimal searchChangeRate,
                         String priceIndexBefore, String priceIndexAfter, LocalDate preliminaryDueOn,
                         LocalDate finalDueOn) {

        public Action {
            reasons = List.copyOf(reasons);
        }
    }
}

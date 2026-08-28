package com.mimococo.marketops.operatingfacts;

import com.mimococo.marketops.shared.Money;
import java.util.Map;
import java.util.Objects;

/**
 * Platform charges over a window, by internal category.
 *
 * <p>Advertising is carried in its own total rather than inside the fee mix,
 * because the profit definition subtracts it separately and because an
 * advertising diagnosis needs it on its own.
 *
 * @param total every charge except advertising
 * @param advertising charges the platform classified as advertising
 * @param byCategory the charge mix per internal category
 * @param settledOnly whether every contributing charge was settled
 * @param evidence what the answer was derived from
 */
public record FeeTotals(
        Money total,
        Money advertising,
        Map<String, Money> byCategory,
        boolean settledOnly,
        FactEvidence evidence) {

    public FeeTotals {
        byCategory = Map.copyOf(Objects.requireNonNull(byCategory, "byCategory"));
    }

    /** An answer nothing contributed to. */
    public static FeeTotals absent() {
        return new FeeTotals(null, null, Map.of(), false, FactEvidence.none());
    }

    /** Whether the answer resolved to a number a caller may use. */
    public boolean available() {
        return evidence.usable();
    }
}

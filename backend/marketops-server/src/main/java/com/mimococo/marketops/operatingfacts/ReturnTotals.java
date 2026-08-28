package com.mimococo.marketops.operatingfacts;

import com.mimococo.marketops.shared.Money;
import java.util.Map;
import java.util.Objects;

/**
 * Returns over a window, with the reason mix that explains them.
 *
 * @param units units returned
 * @param refundAmount amount refunded, or {@code null} when nothing contributed
 * @param lossAmount recorded loss, or {@code null} when nothing contributed
 * @param unitsByReason units returned per internal reason category
 * @param evidence what the answer was derived from
 */
public record ReturnTotals(
        long units,
        Money refundAmount,
        Money lossAmount,
        Map<String, Long> unitsByReason,
        FactEvidence evidence) {

    public ReturnTotals {
        unitsByReason = Map.copyOf(Objects.requireNonNull(unitsByReason, "unitsByReason"));
    }

    /** An answer nothing contributed to. */
    public static ReturnTotals absent() {
        return new ReturnTotals(0L, null, null, Map.of(), FactEvidence.none());
    }

    /** Whether the answer resolved to a number a caller may use. */
    public boolean available() {
        return evidence.usable();
    }
}

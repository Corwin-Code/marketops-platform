package com.mimococo.marketops.operatingfacts;

import com.mimococo.marketops.shared.Money;

/**
 * Sales at one stage of certainty over a window.
 *
 * @param units units sold
 * @param grossAmount gross value, or {@code null} when nothing contributed
 * @param netAmount net value, or {@code null} when nothing contributed
 * @param evidence what the answer was derived from
 */
public record SalesTotals(long units, Money grossAmount, Money netAmount, FactEvidence evidence) {

    /** An answer nothing contributed to. */
    public static SalesTotals absent() {
        return new SalesTotals(0L, null, null, FactEvidence.none());
    }

    /** Whether the answer resolved to a number a caller may use. */
    public boolean available() {
        return evidence.usable();
    }
}

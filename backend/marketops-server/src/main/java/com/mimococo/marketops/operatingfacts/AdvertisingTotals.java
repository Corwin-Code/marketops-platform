package com.mimococo.marketops.operatingfacts;

import com.mimococo.marketops.shared.Money;

/**
 * Advertising cost and its measured effect over a window.
 *
 * <p>Attribution is the marketplace's, not this system's. Attributed revenue is
 * nullable because a platform that publishes spend does not necessarily publish
 * attribution, and inventing one would put a made-up number inside an efficiency
 * metric.
 *
 * @param spendAmount spend in the window, or {@code null} when nothing contributed
 * @param impressions impressions bought, or {@code null}
 * @param clicks clicks bought, or {@code null}
 * @param attributedOrders orders the marketplace attributes, or {@code null}
 * @param attributedRevenue revenue the marketplace attributes, or {@code null}
 * @param evidence what the answer was derived from
 */
public record AdvertisingTotals(
        Money spendAmount,
        Long impressions,
        Long clicks,
        Long attributedOrders,
        Money attributedRevenue,
        FactEvidence evidence) {

    /** An answer nothing contributed to. */
    public static AdvertisingTotals absent() {
        return new AdvertisingTotals(null, null, null, null, null, FactEvidence.none());
    }

    /** Whether the answer resolved to a number a caller may use. */
    public boolean available() {
        return evidence.usable();
    }
}

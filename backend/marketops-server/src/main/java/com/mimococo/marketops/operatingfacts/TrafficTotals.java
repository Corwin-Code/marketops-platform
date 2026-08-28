package com.mimococo.marketops.operatingfacts;

/**
 * Exposure and engagement over a window.
 *
 * <p>Every measure is nullable on its own because platforms publish different
 * subsets, and a diagnosis has to be able to say which part of the funnel it
 * could not see. A null is NOT_AVAILABLE; it is never rendered as zero.
 *
 * @param impressions times the listing was shown, or {@code null}
 * @param clicks clicks on the listing, or {@code null}
 * @param visits visits to the listing, or {@code null}
 * @param addToCart additions to a cart, or {@code null}
 * @param orderedUnits units ordered, or {@code null}
 * @param evidence what the answer was derived from
 */
public record TrafficTotals(
        Long impressions,
        Long clicks,
        Long visits,
        Long addToCart,
        Long orderedUnits,
        FactEvidence evidence) {

    /** An answer nothing contributed to. */
    public static TrafficTotals absent() {
        return new TrafficTotals(null, null, null, null, null, FactEvidence.none());
    }

    /**
     * The strongest available measure of people who reached the listing.
     *
     * <p>Visits are preferred to clicks where both exist, because a visit is
     * closer to the denominator a conversion rate means. When neither exists the
     * answer is absent rather than substituted.
     */
    public Long strongestReachMeasure() {
        return visits != null ? visits : clicks;
    }
}

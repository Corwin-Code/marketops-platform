package com.mimococo.marketops.listingconversion.internal.domain;

import com.mimococo.marketops.listingconversion.EvidencePath;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether an evidence path qualifies for the actual retained-visit total.
 *
 * <p>The detail path qualifies when the complete visit and purchase-link windows are evidenced,
 * including complete empty sets. Source stratification is a separate comparison qualification. The official-summary path qualifies
 * only under a PROVEN equivalence profile that covers numerator, denominator,
 * time attribution, maturity and revision. Official labels alone never qualify,
 * and missing stratification blocks the standardised comparison while leaving
 * the raw total visible; nothing is prorated.
 */
public final class EvidencePathQualification {

    private EvidencePathQualification() {
    }

    /** What is known about the summary equivalence profile that applies. */
    public record SummaryProfile(boolean present, boolean proven, boolean coversNumerator,
                                 boolean coversDenominator, boolean coversTimeAttribution,
                                 boolean coversMaturity, boolean coversRevision) {
        public static SummaryProfile absent() {
            return new SummaryProfile(false, false, false, false, false, false, false);
        }
    }

    /** The reasons a path does not qualify; empty means qualified. */
    public static List<String> disqualifications(EvidencePath path, boolean visitsComplete,
                                                 boolean purchaseLinksComplete, boolean sourceStratified,
                                                 SummaryProfile profile) {
        List<String> reasons = new ArrayList<>();
        if (path == EvidencePath.DETAIL) {
            if (!visitsComplete) {
                reasons.add("VISIT_WINDOW_INCOMPLETE");
            }
            if (!purchaseLinksComplete) {
                reasons.add("PURCHASE_LINK_WINDOW_INCOMPLETE");
            }
            return List.copyOf(reasons);
        }
        if (!profile.present()) {
            reasons.add("EQUIVALENCE_PROFILE_ABSENT");
            return List.copyOf(reasons);
        }
        if (!profile.proven()) {
            reasons.add("EQUIVALENCE_NOT_PROVEN");
        }
        if (!profile.coversNumerator()) {
            reasons.add("EQUIVALENCE_NUMERATOR_UNCOVERED");
        }
        if (!profile.coversDenominator()) {
            reasons.add("EQUIVALENCE_DENOMINATOR_UNCOVERED");
        }
        if (!profile.coversTimeAttribution()) {
            reasons.add("EQUIVALENCE_TIME_ATTRIBUTION_UNCOVERED");
        }
        if (!profile.coversMaturity()) {
            reasons.add("EQUIVALENCE_MATURITY_UNCOVERED");
        }
        if (!profile.coversRevision()) {
            reasons.add("EQUIVALENCE_REVISION_UNCOVERED");
        }
        return List.copyOf(reasons);
    }
}

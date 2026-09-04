package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The complete result of calculating one advertising object, as a pure value.
 *
 * <p>Nothing here has been written anywhere. That is the point: the targeted
 * path and the hourly sweep both produce this same value from the same inputs,
 * so their equivalence can be asserted by comparing two of these rather than by
 * reading rows back and hoping the write path was symmetrical.
 *
 * <p>A calculation may produce several cases for one object, because an object
 * can have several independent causes with different owners. It never produces
 * two cases for the same cause.
 */
public record AdCaseCalculation(
        UUID organizationId,
        UUID adNativeObjectId,
        UUID storeId,
        String platformCode,
        UUID semanticProfileId,
        int lineageGeneration,
        Instant asOf,
        AdPolicySet policies,
        AffectedSet affectedSet,
        UUID affectedSetId,
        List<ScoredCase> cases) {

    /** One case, its lane decision, its rank and the measures behind both. */
    public record ScoredCase(
            AdCaseIdentity identity,
            AdLaneResolver.Decision decision,
            AdPriorityPolicy.Ranking ranking,
            AdMeasure contributionProfit,
            AdMeasure profitPerAdRub,
            AdMeasure officialSpend,
            AdMeasure eligibleTraffic,
            AdLinkedConversion conversion,
            MaxCpc maxCpc,
            AdMeasure attributionGap,
            AdMeasure currentBid,
            AdMeasure recoverableProfit,
            String currencyCode,
            List<VariantDiagnostic> variants) {

        public ScoredCase {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(ranking, "ranking");
            variants = List.copyOf(variants == null ? List.of() : variants);
        }
    }

    /** One variant's share of a case, and whether that share was observed or allocated. */
    public record VariantDiagnostic(
            UUID productVariantId,
            UUID platformListingVariantId,
            boolean officiallyObserved,
            String confidenceState,
            BigDecimal spendAmount,
            Long clicks,
            BigDecimal contributionProfitAmount,
            String currencyCode,
            String sellabilityState,
            String availabilityState,
            boolean criticalSalesUnit) {

        /** The stored basis, derived rather than passed so the two cannot disagree. */
        public String basis() {
            return officiallyObserved ? "OFFICIAL_OBSERVATION" : "ESTIMATED_ALLOCATION";
        }
    }

    public AdCaseCalculation {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(adNativeObjectId, "adNativeObjectId");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(policies, "policies");
        cases = List.copyOf(cases == null ? List.of() : cases);
        long distinctCauses = cases.stream().map(c -> c.identity().cause()).distinct().count();
        if (distinctCauses != cases.size()) {
            throw new IllegalArgumentException(
                    "one calculation cannot produce two cases for the same cause");
        }
    }

    /**
     * The most severe lane any case on this object is in.
     *
     * <p>No cases at all means WATCH rather than a healthy state, because an
     * object we could not calculate is not an object we know is fine.
     */
    public AdvertisingLane mostSevereLane() {
        return cases.stream()
                .map(scored -> scored.decision().lane())
                .max((left, right) -> Integer.compare(left.laneBand(), right.laneBand()))
                .orElse(AdvertisingLane.WATCH);
    }

    /** Whether this calculation raises accountable work. */
    public boolean raisesWork() {
        return cases.stream().anyMatch(scored -> scored.decision().cause().actionable());
    }
}

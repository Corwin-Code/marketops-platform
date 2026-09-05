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
        List<ScoredCase> cases, List<QualificationPeriod> qualificationPeriods, List<PurposeEvidence> purposeEvidence,
        boolean writeQualificationSatisfied) {
    public AdCaseCalculation(UUID organizationId, UUID adNativeObjectId, UUID storeId, String platformCode,
            UUID semanticProfileId, int lineageGeneration, Instant asOf, AdPolicySet policies,
            AffectedSet affectedSet, UUID affectedSetId, List<ScoredCase> cases) {
        this(organizationId, adNativeObjectId, storeId, platformCode, semanticProfileId, lineageGeneration,
                asOf, policies, affectedSet, affectedSetId, cases, List.of(), List.of(), false);
    }
    public record PurposeEvidence(String purpose, String kind, UUID profileId, Instant sourceTime,
            Instant acceptedAt, Instant expiresAt, boolean eligible, List<String> reasonCodes) { }
    public record QualificationPeriod(UUID policyId, Instant from, Instant to, boolean qualified) { }


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
        qualificationPeriods = List.copyOf(qualificationPeriods);
        purposeEvidence = List.copyOf(purposeEvidence);
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

    /** Only the dependencies of a proven one-sided cause authorize this basis. */
    public boolean causeBoundProtectionQualified(ScoredCase scored) {
        if (scored.decision().lane() != AdvertisingLane.PROTECTION || scored.maxCpc().writeGrade()
                || !affectedSet.sufficientForWrite() || !scored.currentBid().sufficientForWrite()
                || !scored.officialSpend().sufficientForWrite() || scored.officialSpend().value().signum() <= 0) return false;
        String dangerKind = switch(scored.identity().cause()) {
            case PROMOTED_VARIANT_NOT_SELLABLE -> "SELLABILITY";
            case PROMOTED_VARIANT_UNAVAILABLE -> "AVAILABILITY";
            default -> null;
        };
        if(dangerKind==null || scored.decision().blockerCodes().contains("CRITICAL_SALES_GUARD_EVIDENCE_UNRESOLVED")) return false;
        return List.of("OFFICIAL_AD_SPEND","AD_OBJECT_CONFIGURATION","AFFECTED_SET",dangerKind).stream()
                .allMatch(kind -> {
                    var exact=purposeEvidence.stream().filter(evidence -> evidence.purpose().equals("PROTECTION_BID_WRITE") && evidence.kind().equals(kind)).toList();
                    return exact.size()==1 && exact.getFirst().eligible() && exact.getFirst().expiresAt()!=null && exact.getFirst().expiresAt().isAfter(asOf);
                });
    }

    /** Whether this calculation raises accountable work. */
    public boolean raisesWork() {
        return cases.stream().anyMatch(scored -> scored.decision().cause().actionable());
    }
}

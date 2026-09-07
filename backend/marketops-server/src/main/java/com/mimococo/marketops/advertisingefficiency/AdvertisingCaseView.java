package com.mimococo.marketops.advertisingefficiency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One advertising case, as the console and any other module read it.
 *
 * <p>Every enum-valued field is a {@code String}. No enum crosses the JSON
 * boundary, for the same reason the availability views do it: a console that
 * deserialised into an enum would fail to render a state a newer backend
 * introduced, and failing to render an unfamiliar danger is worse than
 * displaying its name.
 *
 * <p>The two profit axes appear as two values with two states and there is
 * deliberately no third field combining them. A caller that wants one number
 * will have to write the arithmetic itself, in the open, where a reviewer can
 * see it.
 *
 * <p>Every measure carries its own state, so a client can distinguish "zero"
 * from "not reported" without consulting anything else. That distinction is the
 * whole reason the states travel with the numbers rather than in a separate
 * block.
 */
public record AdvertisingCaseView(
        UUID id,
        UUID storeId,
        String platformCode,
        UUID adNativeObjectId,
        String nativeObjectKind,
        String nativeObjectKey,
        String nativeCampaignKey,
        String nativeObjectName,
        String biddingMode,
        String controlGranularityState,
        int lineageGeneration,
        String lane,
        String protectionTier,
        String causeCode,
        String accountableRoleCode,
        String evidenceState,
        String confidenceState,
        List<String> blockerCodes,
        String contributionProfitState,
        BigDecimal contributionProfitAmount,
        String profitPerAdRubState,
        BigDecimal profitPerAdRubValue,
        String profitCurrencyCode,
        String officialSpendState,
        BigDecimal officialSpendAmount,
        String eligibleTrafficState,
        Long eligibleTrafficCount,
        String adLinkedConversionState,
        BigDecimal adLinkedConversionValue,
        String adLinkedConversionStage,
        String maxCpcState,
        BigDecimal maxCpcAmount,
        String attributionGapState,
        BigDecimal attributionGapRatio,
        String currentBidState,
        BigDecimal currentBidAmount,
        BigDecimal recoverableProfitAmount,
        BigDecimal rankScore,
        String policyVersionDigest,
        String affectedSetDigest,
        String affectedSetResolution,
        int affectedVariantCount,
        Instant asOf,
        Instant calculatedAt,
        String sustainedLane,
        int sustainedCycles,
        Instant sustainedSince,
        List<AdvertisingRankFactorView> rankFactors,
        List<AdvertisingVariantView> variants,
        List<AdvertisingEvidenceView> evidence) {

    public AdvertisingCaseView {
        blockerCodes = List.copyOf(blockerCodes == null ? List.of() : blockerCodes);
        rankFactors = List.copyOf(rankFactors == null ? List.of() : rankFactors);
        variants = List.copyOf(variants == null ? List.of() : variants);
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
    }
}

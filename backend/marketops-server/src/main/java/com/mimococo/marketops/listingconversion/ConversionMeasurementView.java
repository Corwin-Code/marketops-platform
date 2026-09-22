package com.mimococo.marketops.listingconversion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One retained-visit conversion measurement as the console reads it.
 *
 * <p>The primary ratio is present only when DEFINED. The sellability split and
 * the source stratification are auxiliary results shown beside it and never
 * replace it.
 */
public record ConversionMeasurementView(
        UUID id,
        UUID platformListingId,
        int definitionVersion,
        Instant windowStart,
        Instant windowEnd,
        int retentionWindowDays,
        EvidencePath evidencePath,
        boolean pathQualified,
        List<String> qualificationReasonCodes,
        Long visitCount,
        Long retainedPurchaseVisitCount,
        BigDecimal primaryRatio,
        RatioState ratioState,
        boolean maturityReached,
        boolean sourceStratified,
        Map<String, String> sellableSplit,
        List<String> excludedTransitionDays,
        Instant sourceTime,
        Instant acquisitionTime,
        Instant computedAt) {

    public ConversionMeasurementView {
        qualificationReasonCodes = List.copyOf(qualificationReasonCodes == null ? List.of() : qualificationReasonCodes);
        sellableSplit = Map.copyOf(sellableSplit == null ? Map.of() : sellableSplit);
        excludedTransitionDays = List.copyOf(excludedTransitionDays == null ? List.of() : excludedTransitionDays);
    }
}

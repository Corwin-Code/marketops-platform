package com.mimococo.marketops.listingconversion;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A finite, independently observed native promotion inventory for one Listing and period. */
public record PromotionContextObservation(
        Instant coverageStart,
        Instant coverageEnd,
        Instant verificationExpiresAt,
        List<PromotionRecord> records) {

    public PromotionContextObservation {
        if (coverageStart == null || coverageEnd == null || !coverageStart.isBefore(coverageEnd)
                || verificationExpiresAt == null || records == null || records.size() > 64
                || records.stream().anyMatch(java.util.Objects::isNull)) {
            invalid();
        }
        records = List.copyOf(records);
    }

    public record PromotionRecord(
            PromotionTerms declaration,
            String participationState,
            Instant effectiveFrom,
            Instant effectiveTo,
            String newTransactionsState,
            String residualObligationState,
            String originalAuthorityReference,
            Instant originalAuthorityValidUntil,
            Map<String, AxisDemand> axisDemands) {

        public PromotionRecord {
            if (declaration == null || effectiveFrom == null || effectiveTo == null
                    || !effectiveFrom.isBefore(effectiveTo)
                    || participationState == null
                    || !Set.of("PARTICIPATING", "NOT_PARTICIPATING", "UNKNOWN").contains(participationState)
                    || newTransactionsState == null || !Set.of("OPEN", "STOPPED", "UNKNOWN").contains(newTransactionsState)
                    || residualObligationState == null
                    || !Set.of("OUTSTANDING", "CLEARED", "UNKNOWN").contains(residualObligationState)
                    || ("PARTICIPATING".equals(participationState) && !"OPEN".equals(newTransactionsState))
                    || ("NOT_PARTICIPATING".equals(participationState) && "OPEN".equals(newTransactionsState))
                    || ("UNKNOWN".equals(participationState)
                        && (!"UNKNOWN".equals(newTransactionsState) || !"UNKNOWN".equals(residualObligationState)))
                    || ("PARTICIPATING".equals(participationState)
                        && (originalAuthorityReference == null || originalAuthorityValidUntil == null))
                    || (!"PARTICIPATING".equals(participationState)
                        && (originalAuthorityReference != null || originalAuthorityValidUntil != null))) {
                invalid();
            }
            if (originalAuthorityReference != null) {
                MetadataFieldPolicy.requireText("promotionOriginalAuthorityReference", originalAuthorityReference);
            }
            if (axisDemands != null && (axisDemands.size() > 4 || axisDemands.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || !Set.of("CONCURRENT_LISTINGS", "AFFECTED_VARIANTS", "REVENUE_EXPOSURE", "CATEGORY_SHARE")
                            .contains(entry.getKey()) || entry.getValue() == null))) {
                invalid();
            }
            axisDemands = Map.copyOf(axisDemands == null ? Map.of() : axisDemands);
        }
    }

    /** Exact observed demand. It is evidence, never a caller override for a prepared Action. */
    public record AxisDemand(BigDecimal value, String unitCode, String evidenceReference) {
        public AxisDemand {
            if (value == null || value.signum() < 0 || value.precision() > 18 || value.scale() < 0 || value.scale() > 4
                    || unitCode == null || unitCode.length() > 32) {
                invalid();
            }
            MetadataFieldPolicy.requireText("promotionAxisUnitCode", unitCode);
            MetadataFieldPolicy.requireText("promotionAxisEvidenceReference", evidenceReference);
        }
    }

    private static void invalid() {
        throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
    }
}

package com.mimococo.marketops;

import com.mimococo.marketops.listingconversion.PromotionContextObservation;
import com.mimococo.marketops.listingconversion.PromotionTerms;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact request material for a finite promotion-context observation. */
public final class PromotionContextFixture {

    private PromotionContextFixture() {
    }

    /**
     * A complete-enumeration request accepted by the existing promotion-fact endpoint.
     * The target declaration must occur exactly once in {@code records}.
     */
    public static Map<String, Object> completeEnumerationRequest(
            PromotionTerms target,
            String targetParticipationState,
            Instant observedAt,
            Instant coverageStart,
            Instant coverageEnd,
            Instant verificationExpiresAt,
            String evidenceReference,
            List<PromotionContextObservation.PromotionRecord> records) {
        PromotionContextObservation context = new PromotionContextObservation(
                coverageStart, coverageEnd, verificationExpiresAt, records);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("declaration", target);
        request.put("engagementKind", target.engagementKind());
        request.put("nativePromotionKey", target.nativePromotionKey());
        request.put("participationState", targetParticipationState);
        request.put("observedAt", observedAt);
        request.put("evidenceReference", evidenceReference);
        request.put("context", context);
        return Map.copyOf(request);
    }

    /** A one-record complete inventory, useful when no concurrent activity exists. */
    public static Map<String, Object> completeSingleActivityRequest(
            PromotionTerms target,
            String targetParticipationState,
            Instant observedAt,
            Instant coverageStart,
            Instant coverageEnd,
            Instant verificationExpiresAt,
            String evidenceReference,
            String newTransactionsState,
            String residualObligationState,
            String originalAuthorityReference,
            Instant originalAuthorityValidUntil,
            Map<String, PromotionContextObservation.AxisDemand> axisDemands) {
        var record = new PromotionContextObservation.PromotionRecord(
                target, targetParticipationState, coverageStart, coverageEnd,
                newTransactionsState, residualObligationState,
                originalAuthorityReference, originalAuthorityValidUntil, axisDemands);
        return completeEnumerationRequest(target, targetParticipationState, observedAt,
                coverageStart, coverageEnd, verificationExpiresAt, evidenceReference, List.of(record));
    }
}

package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A simple promotion engagement with its obligations and two separate releases. */
public record PromotionEngagementView(
        UUID id,
        UUID storeId,
        UUID platformListingId,
        UUID actionId,
        String engagementKind,
        String nativePromotionKey,
        Map<String, String> terms,
        boolean priceFreeze,
        boolean autoParticipation,
        String termsEvidenceReference,
        boolean adopted,
        Map<String, String> obligations,
        String exitReasonCode,
        UUID exitAuthorizedByUserId,
        Instant exitAuthorizedAt,
        Instant newTransactionsStoppedAt,
        Instant obligationsClearedAt,
        String state,
        long version,
        boolean fullDisclosure,
        String termsDigest,
        UUID sourceContextObservationId,
        String originalAuthorityReference,
        Instant originalAuthorityValidUntil,
        UUID responsibleUserId,
        String adoptionQualificationState,
        Map<String,Object> axisDemands,
        String exitAuthorityReference,
        UUID exitEvidenceId,
        UUID stopEvidenceObservationId,
        UUID obligationEvidenceObservationId) {

    public PromotionEngagementView withFinancialDisclosure(boolean permitted) {
        return new PromotionEngagementView(id,storeId,platformListingId,actionId,engagementKind,nativePromotionKey,
                permitted?terms:Map.of(),priceFreeze,autoParticipation,permitted?termsEvidenceReference:null,adopted,
                permitted?obligations:Map.of(),exitReasonCode,exitAuthorizedByUserId,exitAuthorizedAt,
                newTransactionsStoppedAt,obligationsClearedAt,state,version,permitted,termsDigest,
                sourceContextObservationId,permitted?originalAuthorityReference:null,originalAuthorityValidUntil,
                responsibleUserId,adoptionQualificationState,permitted?axisDemands:Map.of(),
                permitted?exitAuthorityReference:null,exitEvidenceId,stopEvidenceObservationId,obligationEvidenceObservationId);
    }

    public PromotionEngagementView {
        terms = Map.copyOf(terms == null ? Map.of() : terms);
        obligations = Map.copyOf(obligations == null ? Map.of() : obligations);
        axisDemands = Map.copyOf(axisDemands == null ? Map.of() : axisDemands);
    }
}

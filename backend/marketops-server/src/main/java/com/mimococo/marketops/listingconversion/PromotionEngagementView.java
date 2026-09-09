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
        long version) {

    public PromotionEngagementView {
        terms = Map.copyOf(terms == null ? Map.of() : terms);
        obligations = Map.copyOf(obligations == null ? Map.of() : obligations);
    }
}

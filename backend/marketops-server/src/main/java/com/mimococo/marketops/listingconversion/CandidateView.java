package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One comparable candidate of one round. */
public record CandidateView(
        UUID id,
        UUID storeId,
        UUID platformListingId,
        CandidateKind candidateKind,
        String comparisonRoundKey,
        List<String> evidenceReferences,
        Map<String, String> expectedEffect,
        UUID preparedByUserId,
        Instant preparedAt,
        String state,
        long version) {

    public CandidateView {
        evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        expectedEffect = Map.copyOf(expectedEffect == null ? Map.of() : expectedEffect);
    }
}

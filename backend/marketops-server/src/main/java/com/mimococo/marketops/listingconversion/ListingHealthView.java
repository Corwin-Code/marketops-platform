package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Listing Health in three layers and no score.
 *
 * @param necessaryConditions each hard condition with its state
 * @param necessaryState PASS, FAIL or UNKNOWN for the layer as a whole
 * @param eligibility per purpose: ELIGIBLE, INELIGIBLE or UNKNOWN
 * @param opportunities improvement opportunities; an opportunity is not an error
 */
public record ListingHealthView(
        UUID id,
        UUID storeId,
        UUID platformListingId,
        String nativeListingKey,
        int healthVersion,
        List<Condition> necessaryConditions,
        String necessaryState,
        Map<String, String> eligibility,
        List<Opportunity> opportunities,
        String affectedSetState,
        int affectedVariantCount,
        Instant sourceTime,
        Instant acquisitionTime,
        Instant computedAt) {

    public record Condition(String code, String state, String evidenceReference) {
    }

    public record Opportunity(String code, String evidenceReference) {
    }

    public ListingHealthView {
        necessaryConditions = List.copyOf(necessaryConditions == null ? List.of() : necessaryConditions);
        eligibility = Map.copyOf(eligibility == null ? Map.of() : eligibility);
        opportunities = List.copyOf(opportunities == null ? List.of() : opportunities);
    }
}

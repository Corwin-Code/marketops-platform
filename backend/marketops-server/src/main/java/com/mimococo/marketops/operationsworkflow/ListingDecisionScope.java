package com.mimococo.marketops.operationsworkflow;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything the workflow needs to know about a listing action to decide it.
 *
 * <p>Materiality is decided on two independent axes by the listing module
 * against the active calibration package; either axis crossing its trigger
 * routes the final approval to the Owner. The approval validity is the
 * calibration's, never a code default.
 */
public record ListingDecisionScope(
        UUID recommendationId,
        UUID organizationId,
        UUID storeId,
        UUID platformListingId,
        UUID actionId,
        long actionVersion,
        ActionKind actionKind,
        String executionPath,
        String actionState,
        String materialityRoute,
        boolean contentAxisMaterial,
        boolean exposureAxisMaterial,
        UUID calibrationPackageId,
        Integer calibrationVersion,
        Duration approvalValidity,
        String affectedSetDigest,
        String targetTextDigest,
        String currentTextDigest,
        int targetTextLength,
        Boolean kizMarkedDeclared,
        UUID authorUserId,
        UUID reviewerUserId,
        boolean reviewAttested,
        String authorityDocument,
        java.util.Map<String,String> calibrationRecheck,
        java.util.Map<String,String> materialityRecheck,
        java.util.Map<String,String> protectionRecheck,
        String purposeCode,
        String purposeBasisDigest,
        java.time.Instant purposeUseUntil) {

    public ListingDecisionScope {
        calibrationRecheck=java.util.Map.copyOf(calibrationRecheck);
        materialityRecheck=java.util.Map.copyOf(materialityRecheck);
        protectionRecheck=java.util.Map.copyOf(protectionRecheck);
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(platformListingId, "platformListingId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(actionKind, "actionKind");
        Objects.requireNonNull(executionPath, "executionPath");
        Objects.requireNonNull(actionState, "actionState");
        Objects.requireNonNull(materialityRoute, "materialityRoute");
        Objects.requireNonNull(affectedSetDigest, "affectedSetDigest");
        Objects.requireNonNull(authorUserId, "authorUserId");
        Objects.requireNonNull(authorityDocument, "authorityDocument");
    }

    public boolean material() {
        return "MATERIAL_IMPACT".equals(materialityRoute);
    }

    public boolean materialityResolved() {
        return !"MATERIALITY_UNRESOLVED".equals(materialityRoute)
                && calibrationPackageId != null && calibrationVersion != null
                && approvalValidity != null;
    }
}

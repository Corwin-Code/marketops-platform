package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The exact action with its review, binding, launch and occupations.
 *
 * <p>The target Russian text is carried verbatim: it is reviewed as written and
 * never machine-translated.
 *
 * <p>{@code scopeContained} is {@code ops.lc_scope_contained} for this action's
 * listing at read time: an active emergency stop over the organization, the
 * platform, the store, the listing or one of its batches, a shared isolation
 * scope, or an unreleased outcome-protection failure. It is decided on the read
 * of one action only and is {@code null} everywhere else, so a list never pays
 * for it; a launch re-decides it in its own transaction either way.
 */
public record ListingActionView(
        UUID id,
        UUID storeId,
        UUID platformListingId,
        String nativeListingKey,
        UUID candidateId,
        UUID recommendationId,
        long recommendationVersion,
        String recommendationState,
        String affectedSetDigest,
        String affectedSetState,
        int affectedVariantCount,
        String actionKind,
        ExecutionPath executionPath,
        String currentTextDigest,
        String targetText,
        String targetTextDigest,
        Boolean kizMarkedDeclared,
        MaterialityRoute materialityRoute,
        Boolean contentAxisMaterial,
        Boolean exposureAxisMaterial,
        UUID calibrationPackageId,
        Integer calibrationVersion,
        UUID authorUserId,
        ListingActionState state,
        List<Review> reviews,
        Binding binding,
        Launch launch,
        List<Occupation> occupations,
        List<String> bindingGaps,
        Instant createdAt,
        Instant updatedAt,
        long version,
        UUID restoresCommandId,
        String promotionTermsDigest,
        String purposeCode,
        ListingPurposeBasis purposeBasis,
        Boolean scopeContained) {

    public record Review(UUID id, UUID reviewerUserId, String verdict, String reason, Instant reviewedAt,
                         String evaluationPlanDigest) {
    }

    public record Binding(UUID id, UUID approvalDecisionId, UUID guardrailEvaluationId, String bindingDigest,
                          Instant boundAt, Instant expiresAt, String state, String inapplicableReason,
                          String evaluationPlanDigest) {
    }

    public record Launch(UUID id, UUID launchedByUserId, Instant launchedAt) {
    }

    public record Occupation(UUID id, String axisCode, java.math.BigDecimal requestedValue,
                             java.math.BigDecimal occupiedValue, String state, Instant acquiredAt,
                             Instant releasedAt, String releaseBasis) {
    }

    public ListingActionView {
        reviews = List.copyOf(reviews == null ? List.of() : reviews);
        occupations = List.copyOf(occupations == null ? List.of() : occupations);
        bindingGaps = List.copyOf(bindingGaps == null ? List.of() : bindingGaps);
    }
}

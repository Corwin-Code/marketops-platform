package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One proposal, as an operator sees it.
 *
 * <p>The evidence is carried with the proposal rather than fetched separately,
 * because a reviewer deciding whether to change a price on a real marketplace
 * needs to see what the case rests on in the same breath as the proposal. A
 * summary without its evidence invites approval by habit.
 *
 * <p>{@code entityVersionDigest} is what makes an approval specific. A decision
 * authorizes this exact proposal built from these exact facts; if the facts move,
 * the digest no longer matches and the write gate refuses.
 *
 * @param id the proposal
 * @param organizationId owning organization
 * @param storeId store the subject sits on
 * @param subjectKind what the proposal is about
 * @param subjectId the subject
 * @param actionKind what it proposes
 * @param origin whether a model contributed to it
 * @param aiInvocationId the model invocation it came from, or {@code null}
 * @param window the observation window the case was built on
 * @param state where it stands
 * @param priorityScore how urgent it is relative to other work
 * @param proposedParameters the action's parameters
 * @param expectedEffect what it is expected to achieve
 * @param riskLabel how risky it is
 * @param validationHorizonDays how long the effect should be measured for
 * @param entityVersionDigest identity of the facts it was built from
 * @param validUntil when it stops being current
 * @param terminalReason why it ended, or {@code null}
 * @param evidence what the case rests on
 * @param createdAt when it was proposed
 * @param version optimistic-lock version
 */
public record RecommendationView(
        UUID id,
        UUID organizationId,
        UUID storeId,
        SubjectKind subjectKind,
        UUID subjectId,
        ActionKind actionKind,
        String origin,
        UUID aiInvocationId,
        MetricWindow window,
        RecommendationState state,
        BigDecimal priorityScore,
        Map<String, String> proposedParameters,
        Map<String, String> expectedEffect,
        String riskLabel,
        int validationHorizonDays,
        String entityVersionDigest,
        Instant validUntil,
        String terminalReason,
        List<EvidenceRef> evidence,
        Instant createdAt,
        long version) {

    public RecommendationView {
        proposedParameters =
                Map.copyOf(Objects.requireNonNull(proposedParameters, "proposedParameters"));
        expectedEffect = Map.copyOf(Objects.requireNonNull(expectedEffect, "expectedEffect"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    /**
     * One thing the case rests on.
     *
     * <p>Exactly one reference is set. A link that could name two things at once
     * would let a reviewer open the wrong one and believe they had checked the
     * claim.
     *
     * @param metricValueId a canonical value, or {@code null}
     * @param findingId a deterministic finding, or {@code null}
     * @param aiClaimId a validated model claim, or {@code null}
     * @param role how it bears on the proposal
     */
    public record EvidenceRef(UUID metricValueId, UUID findingId, UUID aiClaimId, String role) {
    }
}

package com.mimococo.marketops.operationsworkflow;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The advertising module asking for one bid change to be proposed.
 *
 * <p>The subject is an advertising object, not a listing variant, and the
 * evidence is the case the calculation built. Both travel because the workflow
 * cannot derive either: it does not know what an advertising object is and it
 * did not compute the case.
 *
 * <p>{@code humanReviewWindow} comes from the advertising human-SLO profile
 * rather than the workflow's default. How long a person has to look at a
 * protection case is an advertising policy decision, and a task due in two days
 * for a case that needs answering within the hour would make the queue lie.
 *
 * @param operator who or what is proposing
 * @param organizationId owning organization
 * @param storeId store the object sits on
 * @param adNativeObjectId the object whose bid would change
 * @param caseId the case the candidate came from
 * @param candidateId the provider-normalized candidate
 * @param direction which way the bid moves, and why
 * @param targetBid the provider-normalized target
 * @param window the observation window the case was built on
 * @param priorityScore how urgent it is relative to other work
 * @param expectedEffect what the change is expected to achieve
 * @param riskLabel how risky it is
 * @param validationHorizonDays how long the effect should be measured for
 * @param humanReviewWindow how long a person has, from the advertising SLO profile
 * @param calculationRunId the recorded run that produced it
 * @param entityVersionDigest identity of the advertising facts this rests on
 * @param metricValueEvidenceIds canonical values the case rests on
 */
public record AdvertisingBidProposal(
        String operator,
        UUID organizationId,
        UUID storeId,
        UUID adNativeObjectId,
        UUID caseId,
        UUID candidateId,
        String direction,
        BigDecimal targetBid,
        com.mimococo.marketops.analyticsdecision.MetricWindow window,
        BigDecimal priorityScore,
        Map<String, String> expectedEffect,
        String riskLabel,
        int validationHorizonDays,
        Duration humanReviewWindow,
        UUID calculationRunId,
        String entityVersionDigest,
        List<UUID> metricValueEvidenceIds) {

    public AdvertisingBidProposal {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(adNativeObjectId, "adNativeObjectId");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(targetBid, "targetBid");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(humanReviewWindow, "humanReviewWindow");
        Objects.requireNonNull(calculationRunId, "calculationRunId");
        Objects.requireNonNull(entityVersionDigest, "entityVersionDigest");
        if (!entityVersionDigest.matches("^[0-9a-f]{64}$")) {
            // The database defines this identity and the approval compares
            // itself against it. A value that is not that digest would make the
            // "have the facts moved" check compare two different things.
            throw new IllegalArgumentException(
                    "an entity version digest is the database's sixty-four hex characters");
        }
        expectedEffect = Map.copyOf(expectedEffect == null ? Map.of() : expectedEffect);
        metricValueEvidenceIds = List.copyOf(
                metricValueEvidenceIds == null ? List.of() : metricValueEvidenceIds);
        if (humanReviewWindow.isNegative() || humanReviewWindow.isZero()) {
            throw new IllegalArgumentException(
                    "a review window nobody can meet is not a service level");
        }
    }

    /**
     * The exact parameters the recommendation carries.
     *
     * <p>Built here rather than by the caller so the three keys the parameter
     * contract admits cannot drift apart from the values they describe.
     */
    public Map<String, String> parameters() {
        return Map.of(
                "candidateId", candidateId.toString(),
                "direction", direction,
                "targetBid", targetBid.toPlainString());
    }
}

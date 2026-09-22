package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What the listing conversion module hands the workflow to propose a listing action.
 *
 * <p>The subject is the platform listing, never one of its variants. The entity
 * version digest is the listing module's own identity of the facts reviewed: the
 * complete affected set, the current text and the calibration version, so the
 * approval compares itself against exactly that.
 */
public record ListingActionProposal(
        String operator,
        UUID organizationId,
        UUID storeId,
        UUID platformListingId,
        ActionKind actionKind,
        UUID calculationRunId,
        MetricWindow window,
        BigDecimal priorityScore,
        Map<String, String> proposedParameters,
        Map<String, String> expectedEffect,
        String riskLabel,
        int validationHorizonDays,
        String entityVersionDigest,
        List<UUID> metricValueEvidenceIds) {

    public ListingActionProposal {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(platformListingId, "platformListingId");
        Objects.requireNonNull(actionKind, "actionKind");
        Objects.requireNonNull(calculationRunId, "calculationRunId");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(priorityScore, "priorityScore");
        Objects.requireNonNull(entityVersionDigest, "entityVersionDigest");
        if (actionKind != ActionKind.LISTING_DESCRIPTION_CHANGE
                && actionKind != ActionKind.LISTING_PROMOTION_ACTION) {
            throw new IllegalArgumentException("a listing proposal is a description change or a promotion action");
        }
        if (!entityVersionDigest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(
                    "an entity version digest is sixty-four hex characters");
        }
        proposedParameters = Map.copyOf(proposedParameters == null ? Map.of() : proposedParameters);
        expectedEffect = Map.copyOf(expectedEffect == null ? Map.of() : expectedEffect);
        metricValueEvidenceIds = List.copyOf(metricValueEvidenceIds == null ? List.of() : metricValueEvidenceIds);
    }
}

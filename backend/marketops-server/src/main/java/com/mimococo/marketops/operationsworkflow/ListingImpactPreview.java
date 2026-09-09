package com.mimococo.marketops.operationsworkflow;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A guardrail verdict about a listing action together with what it rested on.
 *
 * @param recommendationId the proposal
 * @param scope the decision scope the verdict read, or {@code null} when none resolved
 * @param unresolved the listing module's own refusals, verbatim
 * @param verdict the recorded verdict
 */
public record ListingImpactPreview(UUID recommendationId, ListingDecisionScope scope,
                                   List<String> unresolved, GuardrailVerdict verdict) {
    public ListingImpactPreview {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(verdict, "verdict");
        unresolved = List.copyOf(unresolved == null ? List.of() : unresolved);
    }
}

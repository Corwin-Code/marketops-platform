package com.mimococo.marketops.aicopilot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One statement a model made, after deterministic validation.
 *
 * <p>A rejected claim is kept rather than discarded. Knowing that a model
 * asserted something ungrounded is exactly what a reviewer needs in order to
 * judge whether to trust the rest of the answer, and discarding it would leave
 * the output looking cleaner than it was.
 *
 * @param claimId identifier of the stored claim
 * @param kind what sort of statement this is
 * @param ordinal position within its kind, as the model produced it
 * @param statement the model's own words
 * @param confidenceLabel the model's stated confidence, or {@code null}
 * @param metricValueRefs canonical values the claim cites
 * @param findingRefs deterministic findings the claim cites
 * @param payload the claim's structured fields
 * @param accepted whether validation accepted the claim
 * @param rejectionCode why validation rejected it, or {@code null}
 */
public record AiClaim(
        UUID claimId,
        AiClaimKind kind,
        int ordinal,
        String statement,
        String confidenceLabel,
        List<UUID> metricValueRefs,
        List<UUID> findingRefs,
        Map<String, String> payload,
        boolean accepted,
        String rejectionCode) {

    public AiClaim {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(statement, "statement");
        metricValueRefs = List.copyOf(Objects.requireNonNull(metricValueRefs, "metricValueRefs"));
        findingRefs = List.copyOf(Objects.requireNonNull(findingRefs, "findingRefs"));
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
    }
}

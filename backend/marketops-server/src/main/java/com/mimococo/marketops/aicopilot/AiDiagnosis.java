package com.mimococo.marketops.aicopilot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of asking a model to explain one subject.
 *
 * <p>The result always exists, even when no model was reached. A degraded answer
 * says so and carries no claims, which is what lets the console show the
 * deterministic diagnosis with an explicit note that the explanation is
 * unavailable rather than an empty panel nobody can interpret.
 *
 * @param invocationId identifier of the recorded invocation
 * @param subjectId the subject the model was asked about
 * @param state how the invocation ended
 * @param failureCode why it did not succeed, or {@code null}
 * @param degraded whether the caller should present this as an unavailable explanation
 * @param providerCode which provider answered, or {@code null}
 * @param modelCode which model answered, or {@code null}
 * @param claims the validated statements, accepted and rejected alike
 * @param startedAt when the invocation began
 * @param completedAt when it ended, or {@code null} while in flight
 */
public record AiDiagnosis(
        UUID invocationId,
        UUID subjectId,
        String state,
        String failureCode,
        boolean degraded,
        String providerCode,
        String modelCode,
        List<AiClaim> claims,
        Instant startedAt,
        Instant completedAt) {

    public AiDiagnosis {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(state, "state");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }

    /** The claims validation accepted, in the order the model produced them. */
    public List<AiClaim> acceptedClaims() {
        return claims.stream().filter(AiClaim::accepted).toList();
    }

    /** The claims validation rejected, kept so a reviewer can weigh the rest. */
    public List<AiClaim> rejectedClaims() {
        return claims.stream().filter(claim -> !claim.accepted()).toList();
    }
}

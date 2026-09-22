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
        int outputSchemaVersion,
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

    /** Exact decimal payloads are text on the Console wire, including rejected model suggestions. */
    public AiDiagnosis consoleView() {
        var wireClaims=claims.stream().map(claim->{
            var payload=new java.util.LinkedHashMap<String,Object>();
            claim.payload().forEach((key,value)->payload.put(key,consoleValue(value)));
            return new AiClaim(claim.claimId(),claim.kind(),claim.ordinal(),claim.statement(),claim.confidenceLabel(),
                    claim.metricValueRefs(),claim.findingRefs(),payload,claim.accepted(),claim.rejectionCode());
        }).toList();
        return new AiDiagnosis(invocationId,subjectId,outputSchemaVersion,state,failureCode,degraded,providerCode,modelCode,
                wireClaims,startedAt,completedAt);
    }

    private static Object consoleValue(Object value) {
        if (value instanceof java.math.BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof java.math.BigInteger integer) return integer.toString();
        if (value instanceof Double || value instanceof Float) return value.toString();
        if (value instanceof Long number && (number>9_007_199_254_740_991L || number< -9_007_199_254_740_991L)) return number.toString();
        if (value instanceof java.util.Map<?,?> map) {
            var result=new java.util.LinkedHashMap<String,Object>();
            map.forEach((key,item)->result.put((String)key,consoleValue(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(AiDiagnosis::consoleValue).toList();
        return value;
    }
}

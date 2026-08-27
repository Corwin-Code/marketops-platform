package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Instant;
import java.util.Objects;

/**
 * Exactly what one acquisition call returned, preserved verbatim.
 *
 * <p>The bytes are the source's bytes and the native status is the source's own
 * status text, kept unparsed. Classification is a separate, deliberately lossy
 * judgement recorded next to the original rather than instead of it, and the
 * one classification that must exist is {@code UNKNOWN_STATE}: an answer the
 * caller cannot understand is evidence to keep, never a success to assume.
 *
 * <p>A defensive copy guards the byte array on both sides of the record, so the
 * evidence a caller stores cannot be rewritten later through a retained
 * reference.
 */
public record AcquisitionResult(
        byte[] body,
        String nativeStatus,
        AcquisitionOutcome outcome,
        Instant sourceTime,
        boolean responseComplete,
        String failureCode,
        boolean retryable,
        java.util.UUID authorityDecisionId,
        Integer callSeq,
        java.util.Map<String,String> responseHeaders) {

    public AcquisitionResult(byte[] body, String nativeStatus, AcquisitionOutcome outcome, Instant sourceTime,
            boolean responseComplete, String failureCode, boolean retryable, java.util.UUID authorityDecisionId, Integer callSeq) {
        this(body,nativeStatus,outcome,sourceTime,responseComplete,failureCode,retryable,authorityDecisionId,callSeq,java.util.Map.of());
    }

    public AcquisitionResult(byte[] body, String nativeStatus, AcquisitionOutcome outcome, Instant sourceTime) {
        this(body, nativeStatus, outcome, sourceTime, true, null, false, null, null);
    }

    /** The gateway, not the adapter, binds the result to the consumed authority. */
    public AcquisitionResult withAuthority(java.util.UUID decision, int sequence) {
        return new AcquisitionResult(body, nativeStatus, outcome, sourceTime, responseComplete,
                failureCode, retryable, decision, sequence, responseHeaders);
    }

    public AcquisitionResult withResponseHeaders(java.util.Map<String,String> headers) {
        return new AcquisitionResult(body,nativeStatus,outcome,sourceTime,responseComplete,failureCode,
                retryable,authorityDecisionId,callSeq,headers);
    }

    /** The closed classification of an acquisition answer. */
    public enum AcquisitionOutcome {
        SUCCESS_BYTES,
        BUSINESS_FAILURE_BYTES,
        UNKNOWN_STATE
    }

    public AcquisitionResult {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(nativeStatus, "nativeStatus");
        Objects.requireNonNull(outcome, "outcome");
        body = body.clone();
        java.util.Map<String,String> safeHeaders = new java.util.LinkedHashMap<>();
        responseHeaders.forEach((name,value) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (java.util.Set.of("content-type","retry-after","x-ratelimit-remaining","x-ratelimit-reset",
                    "x-ratelimit-limit").contains(lower) && value.length()<=256
                    && value.chars().noneMatch(Character::isISOControl)) safeHeaders.put(lower,value);
        });
        responseHeaders = java.util.Map.copyOf(safeHeaders);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}

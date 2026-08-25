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
        Instant sourceTime) {

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
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}

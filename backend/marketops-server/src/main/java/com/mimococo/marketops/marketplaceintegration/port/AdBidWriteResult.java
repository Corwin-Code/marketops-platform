package com.mimococo.marketops.marketplaceintegration.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What one call to a marketplace produced.
 *
 * <p>The adapter's {@link Outcome} is a proposal, not a verdict. The database
 * re-classifies from the recorded operation shape and the exact bytes, so an
 * adapter that decided its own timeout was retriable cannot make it so. This
 * type exists to carry the evidence back, not to decide what it means.
 *
 * <p>{@link Response} refuses to be constructed from something that is not
 * evidence: a status outside the HTTP range, a digest that is not a digest, an
 * evidence class that is neither a fixture nor a provider response, or a header
 * outside the recorded allowlist. Bytes without those are an anecdote.
 */
public record AdBidWriteResult(
        Outcome outcome,
        String nativeStatus,
        String nativeTaskKey,
        BigDecimal observedBid,
        String observedCurrency,
        String observedUnit,
        byte[] body,
        Instant completedAt,
        String errorCode,
        Response response) {

    /** The adapter's proposal about what happened. */
    public enum Outcome {

        /** The platform answered and appeared to accept. */
        ACCEPTED,

        /** The platform answered and definitively refused. */
        REJECTED,

        /** A transport or rate-limit condition that may be tried again. */
        RETRIABLE_ERROR,

        /** No answer arrived within the deadline. */
        TIMEOUT,

        /** An answer arrived that cannot be classified. */
        UNKNOWN_STATE
    }

    /** The transport facts that make a body evidence rather than an anecdote. */
    public record Response(
            int httpStatus,
            Map<String, String> headers,
            String requestDigest,
            String evidenceClass,
            boolean complete) {

        /** The only headers worth keeping, and the only ones that may be kept. */
        private static final Set<String> ALLOWED_HEADERS = Set.of(
                "content-type", "retry-after", "x-request-id", "etag", "x-version-id");

        public Response {
            Objects.requireNonNull(headers, "headers");
            if (httpStatus < 100 || httpStatus > 599) {
                throw new IllegalArgumentException("an HTTP status outside 100..599 is not one");
            }
            if (requestDigest == null || !requestDigest.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException(
                        "a response must name the exact request that produced it");
            }
            if (!"PROTOCOL_FIXTURE".equals(evidenceClass)
                    && !"PROVIDER_RESPONSE".equals(evidenceClass)) {
                throw new IllegalArgumentException("an evidence class must say which it is");
            }
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (!ALLOWED_HEADERS.contains(name)) {
                    throw new IllegalArgumentException(
                            "a response header outside the recorded allowlist is not retained");
                }
                String value = header.getValue();
                if (value == null || value.length() > 256
                        || value.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("a response header value is out of bounds");
                }
            }
            headers = Map.copyOf(headers);
        }
    }

    public AdBidWriteResult {
        Objects.requireNonNull(outcome, "outcome");
        body = body == null ? null : body.clone();
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    /** Attach the exact bytes and transport facts to a classified answer. */
    public AdBidWriteResult withResponse(byte[] exactBody, Response transport) {
        return new AdBidWriteResult(outcome, nativeStatus, nativeTaskKey, observedBid,
                observedCurrency, observedUnit, exactBody, completedAt, errorCode, transport);
    }

    /** Whether asynchronous work the platform accepted has no handle to follow. */
    public boolean taskFinished() {
        return outcome == Outcome.ACCEPTED && nativeTaskKey == null;
    }

    /** A refusal made before any socket was opened. */
    public static AdBidWriteResult refusedBeforeDispatch(String errorCode, Instant at) {
        return new AdBidWriteResult(Outcome.REJECTED, null, null, null, null, null,
                null, at, errorCode, null);
    }
}

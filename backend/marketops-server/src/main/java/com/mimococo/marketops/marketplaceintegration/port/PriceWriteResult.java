package com.mimococo.marketops.marketplaceintegration.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * What a platform answered, classified but not interpreted.
 *
 * <p>The classification is about the call, never about the command. An
 * {@code ACCEPTED} answer says the platform took the request, which is not the
 * same as the platform holding the intended value — only a readback says that,
 * and only the command's own transition rules may draw the conclusion.
 *
 * <p>{@code UNKNOWN_STATE} is the honest answer to a timeout. The call may have
 * reached the marketplace and may have changed the price. Collapsing it into a
 * failure would authorize a retry that writes twice.
 *
 * @param outcome how the answer classifies
 * @param nativeStatus the platform's own words, or {@code null}
 * @param nativeTaskKey the platform's handle for asynchronous work, or {@code null}
 * @param observedPrice the price a readback observed, or {@code null}
 * @param observedCurrency currency a readback observed, or {@code null}
 * @param body the answer's bytes, kept as evidence
 * @param completedAt when the answer arrived
 * @param errorCode why the call did not succeed, or {@code null}
 */
public record PriceWriteResult(
        Outcome outcome,
        String nativeStatus,
        String nativeTaskKey,
        BigDecimal observedPrice,
        String observedCurrency,
        byte[] body,
        Instant completedAt,
        String errorCode,
        Response response) {

    public PriceWriteResult(Outcome outcome, String nativeStatus, String nativeTaskKey,
                            BigDecimal observedPrice, String observedCurrency, byte[] body,
                            Instant completedAt, String errorCode) {
        this(outcome, nativeStatus, nativeTaskKey, observedPrice, observedCurrency, body,
                completedAt, errorCode, null);
    }

    /** Attach exact transport bytes even when parsing or classification failed. */
    public PriceWriteResult withResponse(byte[] exactBody, Response transport) {
        return new PriceWriteResult(outcome, nativeStatus, nativeTaskKey, observedPrice,
                observedCurrency, exactBody, completedAt, errorCode, transport);
    }

    /** Only allowlisted non-secret response metadata may leave the adapter. */
    public record Response(int httpStatus, java.util.Map<String, String> headers,
                           String requestDigest, String evidenceClass, boolean complete) {
        public Response(int httpStatus, java.util.Map<String, String> headers, String requestDigest, String evidenceClass) {
            this(httpStatus, headers, requestDigest, evidenceClass, true);
        }
        public Response {
            headers = java.util.Map.copyOf(headers);
            if (httpStatus < 100 || httpStatus > 599
                    || requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")
                    || !java.util.Set.of("PROTOCOL_FIXTURE", "PROVIDER_RESPONSE").contains(evidenceClass)
                    || headers.entrySet().stream().anyMatch(entry ->
                        !java.util.Set.of("content-type", "retry-after", "x-request-id", "etag", "x-version-id")
                                .contains(entry.getKey())
                        || entry.getValue().length() > 256
                        || entry.getValue().chars().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("invalid provider response metadata");
            }
        }
    }

    public PriceWriteResult {
        Objects.requireNonNull(outcome, "outcome");
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** How one answer classifies. */
    public enum Outcome {

        /** The platform took the request, or reported the value. */
        ACCEPTED,

        /** The platform refused permanently. */
        REJECTED,

        /** A retriable transport or rate-limit condition occurred. */
        RETRIABLE_ERROR,

        /** The call timed out; whether it took effect is unknown. */
        TIMEOUT,

        /** The answer cannot be classified. */
        UNKNOWN_STATE
    }

    /** Whether the platform reported the asynchronous work as finished. */
    public boolean taskFinished() {
        return outcome == Outcome.ACCEPTED && nativeTaskKey == null;
    }
}

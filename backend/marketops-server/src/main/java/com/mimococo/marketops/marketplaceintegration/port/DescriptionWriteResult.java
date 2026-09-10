package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What came back from a description call, with the exact bytes and transport facts.
 *
 * <p>The outcome proposed here is a hint. The database re-classifies from the
 * frozen operation shape, so what matters is that the bytes and the transport
 * facts arrive intact and bound to the request that produced them.
 */
public record DescriptionWriteResult(
        Outcome outcome,
        String nativeStatus,
        String nativeTaskKey,
        byte[] body,
        Instant completedAt,
        String errorCode,
        Integer retryAfterSeconds,
        Response response) {

    public enum Outcome {
        ACCEPTED,
        REJECTED,
        RETRIABLE_ERROR,
        TIMEOUT,
        UNKNOWN_STATE
    }

    public record Response(int httpStatus, Map<String, String> headers, String requestDigest,
                           String evidenceClass, boolean complete) {

        private static final Set<String> ALLOWED_HEADERS = Set.of(
                "content-type", "retry-after", "item-retry-after", "x-ratelimit-retry",
                "x-request-id", "etag", "x-version-id");

        public Response {
            Objects.requireNonNull(headers, "headers");
            if (httpStatus < 100 || httpStatus > 599) {
                throw new IllegalArgumentException("an HTTP status outside 100..599 is not one");
            }
            if (requestDigest == null || !requestDigest.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException(
                        "a response must name the exact request that produced it");
            }
            if (!"PROTOCOL_FIXTURE".equals(evidenceClass) && !"PROVIDER_RESPONSE".equals(evidenceClass)) {
                throw new IllegalArgumentException("an evidence class must say which it is");
            }
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (!ALLOWED_HEADERS.contains(name)) {
                    throw new IllegalArgumentException(
                            "a response header outside the recorded allowlist is not retained");
                }
                String value = header.getValue();
                if (value == null || value.length() > 8192
                        || value.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("a response header value is out of bounds");
                }
            }
            headers = Map.copyOf(headers);
        }
    }

    public DescriptionWriteResult {
        Objects.requireNonNull(outcome, "outcome");
        body = body == null ? null : body.clone();
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    public static DescriptionWriteResult refusedBeforeDispatch(String errorCode, Instant at) {
        return new DescriptionWriteResult(Outcome.REJECTED, null, null, null, at, errorCode, null, null);
    }
}

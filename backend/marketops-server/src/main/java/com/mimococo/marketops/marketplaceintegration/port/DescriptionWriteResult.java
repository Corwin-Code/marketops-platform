package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Instant;
import java.util.List;
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

    /**
     * The retained response facts.
     *
     * <p>{@code unresolvedTiming} holds a provider wait header that cannot be read as one value:
     * the exact field-line values in arrival order, with {@code null} for each line the transport
     * withheld as out of bounds. It is stored as a JSON array, which the durable timing parser
     * classifies as unknown timing; it never becomes an absent, zero or shorter wait.
     */
    public record Response(int httpStatus, Map<String, String> headers, String requestDigest,
                           String evidenceClass, boolean complete, Map<String, List<String>> unresolvedTiming) {

        private static final Set<String> ALLOWED_HEADERS = Set.of(
                "content-type", "retry-after", "item-retry-after", "x-ratelimit-retry",
                "x-request-id", "etag", "x-version-id");
        public static final Set<String> TIMING_HEADERS = Set.of("retry-after", "item-retry-after", "x-ratelimit-retry");
        private static final int MAX_FIELD_LINES = 128;

        public Response(int httpStatus, Map<String, String> headers, String requestDigest,
                        String evidenceClass, boolean complete) {
            this(httpStatus, headers, requestDigest, evidenceClass, complete, Map.of());
        }

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
            Objects.requireNonNull(unresolvedTiming, "unresolvedTiming");
            Set<String> retainedNames = new java.util.HashSet<>();
            headers.keySet().forEach(name -> retainedNames.add(name.toLowerCase(Locale.ROOT)));
            Map<String, List<String>> unresolved = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, List<String>> timing : unresolvedTiming.entrySet()) {
                String name = timing.getKey() == null ? null : timing.getKey().toLowerCase(Locale.ROOT);
                if (name == null || !TIMING_HEADERS.contains(name) || retainedNames.contains(name)
                        || unresolved.containsKey(name)) {
                    throw new IllegalArgumentException("unresolved timing names one wait header once");
                }
                List<String> lines = timing.getValue();
                if (lines == null || lines.isEmpty() || lines.size() > MAX_FIELD_LINES
                        || (lines.size() == 1 && lines.getFirst() != null)) {
                    throw new IllegalArgumentException("unresolved timing is several field lines or a withheld one");
                }
                for (String value : lines) {
                    if (value != null && (value.length() > 8192 || value.chars().anyMatch(Character::isISOControl))) {
                        throw new IllegalArgumentException("a response header value is out of bounds");
                    }
                }
                unresolved.put(name, java.util.Collections.unmodifiableList(new java.util.ArrayList<>(lines)));
            }
            unresolvedTiming = java.util.Collections.unmodifiableMap(unresolved);
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

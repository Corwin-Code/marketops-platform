package com.mimococo.marketops.shared.port;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** All provider HTTP uses a destination decision made before resolving any secret. */
public interface OutboundHttp {
    Plan prepare(Destination destination);

    Response exchange(Plan plan, Map<String, String> headers) throws IOException, InterruptedException;

    /** An opaque, short-lived destination decision with pinned DNS answers. */
    interface Plan { }

    record Request(Plan plan, Map<String, String> headers) {
        public Request { headers = Map.copyOf(headers); }
        @Override public String toString() { return "OutboundRequest[redacted]"; }
    }

    static void requireHeaderTemplate(String value) {
        if (value == null || value.length() > 8192 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid outbound header template");
        }
    }

    /** Header names are declared here; their secret values are supplied only after preparation. */
    record Destination(String policyKey, URI uri, String method, Set<String> headerNames,
                       byte[] body, int timeoutMillis, int maxResponseBytes) {
        public Destination {
            headerNames = Set.copyOf(headerNames);
            body = body.clone();
        }
        @Override public byte[] body() { return body.clone(); }
        @Override public String toString() { return "OutboundDestination[redacted]"; }
    }

    /**
     * Incomplete bytes are an exact prefix and can never establish a successful observation.
     *
     * <p>{@code withheldHeaders} counts, per retained header name, the field lines whose value
     * arrived outside the transport bounds. Only that fact is kept, never the value, so a
     * consumer can tell a header that was absent from one that was present but not retainable.
     */
    record Response(int statusCode, byte[] body, Map<String, List<String>> headers,
                    boolean complete, String failureCode, Map<String, Integer> withheldHeaders) {
        public Response(int statusCode, byte[] body, Map<String, List<String>> headers,
                        boolean complete, String failureCode) {
            this(statusCode, body, headers, complete, failureCode, Map.of());
        }

        public Response {
            body = body.clone();
            Map<String, List<String>> copy = new java.util.LinkedHashMap<>();
            headers.forEach((key, value) -> copy.merge(key.toLowerCase(java.util.Locale.ROOT), List.copyOf(value),
                    (left, right) -> java.util.stream.Stream.concat(left.stream(), right.stream()).toList()));
            headers = Map.copyOf(copy);
            Map<String, Integer> withheld = new java.util.LinkedHashMap<>();
            java.util.Objects.requireNonNull(withheldHeaders, "withheldHeaders").forEach((key, count) -> {
                if (key == null || key.isBlank() || count == null || count < 1) {
                    throw new IllegalArgumentException("a withheld header is a named, positive count");
                }
                withheld.merge(key.toLowerCase(java.util.Locale.ROOT), count, Integer::sum);
            });
            withheldHeaders = Map.copyOf(withheld);
        }
        @Override public byte[] body() { return body.clone(); }
        public Optional<String> firstHeader(String name) {
            List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return values == null || values.size() != 1 ? Optional.empty() : Optional.of(values.getFirst());
        }
        @Override public String toString() { return "OutboundResponse[status=" + statusCode + ",complete=" + complete + "]"; }
    }
}

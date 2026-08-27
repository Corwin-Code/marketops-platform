package com.mimococo.marketops.marketplaceintegration.adapter.objectstorage;

import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.io.IOException;
import java.net.URI;
import com.mimococo.marketops.shared.port.OutboundHttp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable Raw custody in an S3-compatible managed object store.
 *
 * <p>Three operations are used and no more: conditional put, get and head. The
 * store is treated as write-once by the caller's discipline and by the
 * conditional put this adapter issues; there is deliberately no delete, because
 * Raw evidence leaves custody through a governed retention decision rather than
 * through an interface a worker can reach.
 *
 * <p>The adapter is fail-closed on configuration. Endpoint, region, bucket,
 * access key and a resolvable secret reference must all be present; any of them
 * missing refuses the operation instead of attempting an unauthenticated call.
 * The secret is resolved at the moment of use and cleared immediately after the
 * signature is computed.
 *
 * <p>Every failure is reported as a stable code. The provider's own message can
 * name a bucket, a key or an endpoint, so it is never propagated to a caller and
 * never logged; only the status class and the correlation identifier are.
 */
public final class S3CompatibleObjectStorage implements ObjectStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3CompatibleObjectStorage.class);

    /** The locator shape the custody schema accepts. */
    private static final Pattern LOCATOR = Pattern.compile(
            "^object-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,6}$");

    private static final String SCHEME = "object-ref://";

    /** Header naming the payload digest, which the scheme signs. */
    private static final String CONTENT_SHA256_HEADER = "x-amz-content-sha256";

    /** Header naming the request instant, which the scheme signs. */
    private static final String DATE_HEADER = "x-amz-date";

    /** Conditional put: succeed only when the key does not already exist. */
    private static final String IF_NONE_MATCH_HEADER = "if-none-match";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final OutboundHttp httpClient;
    private final ObjectStorageProperties properties;
    private final SecretResolverPort secrets;
    private final Clock clock;

    public S3CompatibleObjectStorage(OutboundHttp httpClient,
                                     ObjectStorageProperties properties,
                                     SecretResolverPort secrets,
                                     Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PutOutcome putIfAbsent(String objectRef, byte[] body) {
        String key = keyOf(objectRef);
        OutboundHttp.Response response = send(
                "PUT", key, body, Map.of(IF_NONE_MATCH_HEADER, "*"));
        int status = response.statusCode();
        if (status == 200 || status == 201) {
            return PutOutcome.STORED;
        }
        // A refused precondition is the store telling us the key already holds
        // content. That is the write-once guarantee working, not a failure.
        if (status == 412 || status == 409) {
            return PutOutcome.ALREADY_PRESENT;
        }
        throw refusal("raw_custody_put_failed", status);
    }

    @Override
    public Optional<byte[]> read(String objectRef) {
        String key = keyOf(objectRef);
        OutboundHttp.Response response = send(
                "GET", key, new byte[0], Map.of());
        int status = response.statusCode();
        if (status == 200) {
            return Optional.of(response.body());
        }
        if (status == 404) {
            return Optional.empty();
        }
        throw refusal("raw_custody_get_failed", status);
    }

    @Override
    public boolean verify(String objectRef, String sha256Hex) {
        return read(objectRef)
                .map(stored -> Digest.ofBytes(stored).equalsIgnoreCase(sha256Hex))
                .orElse(false);
    }

    private OutboundHttp.Response send(String method,
                                     String key,
                                     byte[] body,
                                     Map<String, String> extraHeaders) {
        requireConfigured();
        Instant now = clock.instant();
        URI endpoint = URI.create(properties.getEndpoint());
        String canonicalUri = "/" + properties.getBucket() + "/" + encodeKey(key);

        SortedMap<String, String> headers = SignatureV4.canonicalHeaderMap(mergedHeaders(
                endpoint, body, now, extraHeaders));
        String canonicalRequest = SignatureV4.canonicalRequest(
                method, canonicalUri, "", headers, headers.get(CONTENT_SHA256_HEADER));
        String stringToSign =
                SignatureV4.stringToSign(now, properties.getRegion(), canonicalRequest);

        OutboundHttp.Plan plan;
        try {
            java.util.Set<String> names = new java.util.HashSet<>(headers.keySet());
            names.remove("host"); names.add("Authorization");
            plan = httpClient.prepare(new OutboundHttp.Destination("object-storage:raw",
                    URI.create(endpoint + canonicalUri), method, names, body,
                    Math.toIntExact(REQUEST_TIMEOUT.toMillis()), 8 * 1024 * 1024));
        } catch (RuntimeException invalidDestination) {
            throw refusal("raw_custody_destination_refused", 0);
        }

        char[] secret = secrets.resolve(properties.getCredentialReference())
                .orElseThrow(() -> refusal("raw_custody_secret_unresolvable", 0));
        String authorization;
        try {
            authorization = SignatureV4.authorization(properties.getAccessKeyId(), secret, now,
                    properties.getRegion(), headers, stringToSign);
        } finally {
            Arrays.fill(secret, '\0');
        }

        Map<String, String> requestHeaders = new java.util.HashMap<>(headers);
        requestHeaders.remove("host");
        requestHeaders.put("Authorization", authorization);
        try {
            OutboundHttp.Response response = httpClient.exchange(plan, requestHeaders);
            if (!response.complete()) throw refusal("raw_custody_response_incomplete", response.statusCode());
            return response;
        } catch (IllegalArgumentException invalidDestination) {
            throw refusal("raw_custody_destination_refused", 0);
        } catch (IOException failure) {
            throw refusal("raw_custody_transport_failed", 0);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw refusal("raw_custody_interrupted", 0);
        }
    }

    private Map<String, String> mergedHeaders(URI endpoint,
                                              byte[] body,
                                              Instant now,
                                              Map<String, String> extraHeaders) {
        return java.util.stream.Stream.concat(
                        Map.of(
                                "host", hostHeader(endpoint),
                                DATE_HEADER, SignatureV4.amzDateTime(now),
                                CONTENT_SHA256_HEADER, SignatureV4.hashPayload(body))
                                .entrySet().stream(),
                        extraHeaders.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String hostHeader(URI endpoint) {
        return endpoint.getPort() < 0
                ? endpoint.getHost()
                : endpoint.getHost() + ":" + endpoint.getPort();
    }

    private void requireConfigured() {
        boolean configured = properties.getEndpoint() != null
                && properties.getRegion() != null
                && properties.getBucket() != null
                && properties.getAccessKeyId() != null
                && properties.getCredentialReference() != null;
        configured = configured
                && properties.getEndpoint().matches("https://[a-z0-9][a-z0-9.-]{0,252}")
                && properties.getRegion().matches("[a-z0-9][a-z0-9-]{0,62}")
                && properties.getBucket().matches("[a-z0-9][a-z0-9.-]{1,62}")
                && properties.getAccessKeyId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                && properties.getCredentialReference().matches("secret-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,4}");
        if (!configured) {
            throw refusal("raw_custody_not_configured", 0);
        }
    }

    /** Turn a custody locator into the object key beneath the bucket. */
    private static String keyOf(String objectRef) {
        if (objectRef == null || !LOCATOR.matcher(objectRef).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String withoutScheme = objectRef.substring(SCHEME.length()).toLowerCase(Locale.ROOT);
        int firstSeparator = withoutScheme.indexOf('/');
        return withoutScheme.substring(firstSeparator + 1);
    }

    /** Encode a key for the request line, preserving its separators. */
    private static String encodeKey(String key) {
        return java.util.Arrays.stream(key.split("/", -1))
                .map(SignatureV4::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    private OperationRejectedException refusal(String event, int status) {
        log.atError()
                .addKeyValue("event", event)
                .addKeyValue("statusClass", status == 0 ? "none" : (status / 100) + "xx")
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Object storage operation refused");
        return OperationRejectedException.of(ErrorCode.OBJECT_STORAGE_VERIFICATION_FAILED);
    }
}

package com.mimococo.marketops.marketplaceintegration.adapter.http;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.mimococo.marketops.shared.CorrelationId;
import java.io.IOException;
import java.net.URI;
import com.mimococo.marketops.shared.port.OutboundHttp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one outbound doorway to a marketplace, driven entirely by recorded
 * evidence.
 *
 * <p>Nothing about a marketplace is written here. The origin, the method, the
 * path, the query, the body, the authentication headers, the page size and the
 * rate limit are all facts somebody recorded from official documentation and a
 * real account, each carrying its own verification state. An endpoint that has
 * not been verified has no reachable specification at all, so the fail-closed
 * behaviour is the absence of a call rather than a check somebody could forget.
 *
 * <p>The answer is preserved exactly. The bytes are the bytes, the native status
 * is the transport's own words, and an answer that cannot be classified stays
 * {@code UNKNOWN_STATE}: for a read that is conservative, because a timeout
 * genuinely does not say whether the source produced anything.
 *
 * <p>No secret survives the call. Values are resolved at the moment of use,
 * placed in a header, and the arrays are cleared in a finally block; nothing
 * about a credential reaches a log record, and the returned result carries only
 * what the source sent.
 */
public final class PlatformHttpAcquisitionAdapter implements AcquisitionPort {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformHttpAcquisitionAdapter.class);

    /** Placeholder carrying the acquisition cursor into a recorded template. */
    private static final String CURSOR_PLACEHOLDER = "cursor";

    /** Placeholder carrying the requested page size. */
    private static final String LIMIT_PLACEHOLDER = "limit";

    /** Placeholder carrying the marketplace account's own identifier. */
    private static final String ACCOUNT_KEY_PLACEHOLDER = "accountKey";

    /** Placeholder a template uses when a platform has no cursor yet. */
    private static final String INITIAL_CURSOR = "";

    /** Page size requested when a recorded template asks for one. */
    private static final String DEFAULT_PAGE_SIZE = "100";

    private final OutboundHttp httpClient;
    private final PlatformCallSpecRepository specs;
    private final SecretResolverPort secrets;
    private final Clock clock;

    public PlatformHttpAcquisitionAdapter(OutboundHttp httpClient,
                                          PlatformCallSpecRepository specs,
                                          SecretResolverPort secrets,
                                          Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.specs = Objects.requireNonNull(specs, "specs");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AcquisitionResult acquire(AcquisitionRequest request) {
        Instant startedAt = clock.instant();
        if (!request.callAuthorityExpiresAt().isAfter(startedAt)) return refused("call_authority_expired",startedAt);
        Optional<String> evidence=specs.acquisitionEvidenceDigest(request.endpointId(),request.credentialId());
        if (evidence.isEmpty()) return refused("account_evidence_not_current",startedAt);
        Optional<EndpointCallSpec> found = specs.findVerifiedSpec(request.endpointId());
        if (found.isEmpty()) {
            return refused("endpoint_not_verified", startedAt);
        }
        EndpointCallSpec spec = found.get();

        List<AuthHeaderSpec> authHeaders = specs.verifiedAuthHeaders(spec.platformCode(), "READ");
        if (authHeaders.isEmpty()) {
            return refused("authentication_not_recorded", startedAt);
        }

        if (!specs.reserveCallBudget(spec.endpointId())) {
            // Reporting the wait as an unclassified answer is deliberate: the
            // call did not happen, and a caller must not record it as a source
            // that returned nothing.
            return refused("rate_limit_window_exhausted", startedAt);
        }

        Map<String, String> placeholders = placeholders(request, spec);
        OutboundHttp.Request builder;
        List<char[]> resolvedSecrets = new ArrayList<>();
        try {
            builder = buildRequest(spec, placeholders, request, authHeaders, resolvedSecrets,evidence.get());
        } catch (RuntimeException refusal) {
            return refused("request_could_not_be_built", startedAt);
        } finally {
            resolvedSecrets.forEach(secret -> Arrays.fill(secret, '\0'));
        }
        if (builder == null) {
            return refused("credential_unresolvable", startedAt);
        }
        if (!request.callAuthorityExpiresAt().isAfter(clock.instant())) return refused("call_authority_expired",startedAt);
        if (!evidence.equals(specs.acquisitionEvidenceDigest(request.endpointId(),request.credentialId()))) return refused("account_evidence_changed",startedAt);

        try {
            OutboundHttp.Response response =
                    httpClient.exchange(builder.plan(), builder.headers());
            return classify(response, spec, startedAt);
        } catch (IOException transportFailure) {
            // The call may or may not have reached the source. That is exactly
            // what UNKNOWN_STATE means, and treating it as a failure would let a
            // caller acknowledge a page that might have been produced.
            return refused("transport_failed", startedAt);
        } catch (IllegalArgumentException rejectedPlan) {
            return refused("outbound_plan_rejected", startedAt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return refused("interrupted", startedAt);
        }
    }

    private OutboundHttp.Request buildRequest(EndpointCallSpec spec,
                                             Map<String, String> placeholders,
                                             AcquisitionRequest request,
                                             List<AuthHeaderSpec> authHeaders,
                                             List<char[]> resolvedSecrets,String evidenceDigest) {
        String path = RequestTemplate.render(
                spec.pathTemplate(), placeholders, RequestTemplate.Escaping.URL);
        String query = RequestTemplate.render(
                spec.queryTemplate(), placeholders, RequestTemplate.Escaping.URL);
        String body = RequestTemplate.render(
                spec.bodyTemplate(), placeholders, RequestTemplate.Escaping.JSON);

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", spec.responseContentType() == null ? "application/json" : spec.responseContentType());
        if (body != null) headers.put("Content-Type", "application/json");
        java.util.Set<String> names = new java.util.HashSet<>(headers.keySet());
        authHeaders.forEach(header -> { names.add(header.headerName()); OutboundHttp.requireHeaderTemplate(header.valueTemplate()); });
        headers.values().forEach(OutboundHttp::requireHeaderTemplate);
        OutboundHttp.Plan plan = httpClient.prepare(new OutboundHttp.Destination(
                "platform:" + spec.platformCode() + ":read",
                URI.create(spec.baseUrl() + path + (query == null ? "" : "?" + query)), spec.httpMethod(), names,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                spec.requestTimeoutMillis(), Math.toIntExact(spec.maxResponseBytes())));

        if (!specs.acquisitionEvidenceDigest(request.endpointId(),request.credentialId()).filter(evidenceDigest::equals).isPresent()) {
            throw new IllegalArgumentException("verified configuration changed before credential resolution");
        }

        for (AuthHeaderSpec header : authHeaders) {
            Optional<String> value = headerValue(header, request, resolvedSecrets);
            if (value.isEmpty()) {
                return null;
            }
            headers.put(header.headerName(), value.get());
        }
        return new OutboundHttp.Request(plan, headers);
    }

    private Optional<String> headerValue(AuthHeaderSpec header,
                                         AcquisitionRequest request,
                                         List<char[]> resolvedSecrets) {
        return switch (header.valueSource()) {
            case LITERAL -> Optional.of(header.valueTemplate());
            case ACCOUNT_NATIVE_KEY -> specs.accountNativeKey(request.credentialId())
                    .map(key -> header.valueTemplate().replace("{value}", key));
            case RESOLVED_SECRET -> {
                Optional<String> reference = specs.activeSecretReference(
                        request.credentialId(), header.credentialPurpose());
                if (reference.isEmpty()) {
                    yield Optional.empty();
                }
                Optional<char[]> secret = secrets.resolve(reference.get());
                if (secret.isEmpty()) {
                    yield Optional.empty();
                }
                resolvedSecrets.add(secret.get());
                yield Optional.of(
                        header.valueTemplate().replace("{value}", new String(secret.get())));
            }
        };
    }

    private Map<String, String> placeholders(AcquisitionRequest request, EndpointCallSpec spec) {
        Map<String, String> values = new HashMap<>();
        values.put(CURSOR_PLACEHOLDER,
                specs.checkpointPosition(request.jobId()).orElse(INITIAL_CURSOR));
        values.put(LIMIT_PLACEHOLDER, DEFAULT_PAGE_SIZE);
        values.put(ACCOUNT_KEY_PLACEHOLDER,
                specs.accountNativeKey(request.credentialId()).orElse(INITIAL_CURSOR));
        // The endpoint code is included so a recorded template can address a
        // family of resources that differ only by registry name.
        values.put("endpointCode", spec.endpointCode());
        return values;
    }

    /**
     * Classify one answer from its transport status alone.
     *
     * <p>The rules are HTTP semantics rather than platform facts, which is why
     * they can be written here without inventing anything: a success carries
     * bytes, a client refusal carries a business answer worth keeping, and a
     * timeout, a rate-limit response or a server failure leaves the caller
     * unable to say whether the source produced anything.
     */
    private AcquisitionResult classify(OutboundHttp.Response response,
                                       EndpointCallSpec spec,
                                       Instant startedAt) {
        int status = response.statusCode();
        byte[] body = response.body() == null ? new byte[0] : response.body();
        boolean complete = response.complete() && body.length <= spec.maxResponseBytes();
        if (!complete) body = Arrays.copyOf(body, (int) Math.min(body.length,spec.maxResponseBytes()));
        String contentType = response.firstHeader("content-type").orElse("").split(";",2)[0].trim();
        boolean declaredContentType = spec.responseContentType()!=null && spec.responseContentType().equalsIgnoreCase(contentType);
        String failureCode = !complete ? "RESPONSE_INCOMPLETE" : declaredContentType ? response.failureCode() : "UNEXPECTED_CONTENT_TYPE";
        String nativeStatus = "HTTP " + status;
        AcquisitionResult.AcquisitionOutcome outcome;
        if (!complete || !declaredContentType) {
            outcome = AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE;
        } else if (status >= 200 && status < 300) {
            outcome = AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES;
        } else if (status == 408 || status == 429 || status >= 500) {
            outcome = AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE;
        } else {
            outcome = AcquisitionResult.AcquisitionOutcome.BUSINESS_FAILURE_BYTES;
        }
        log.atInfo()
                .addKeyValue("event", "acquisition_call_completed")
                .addKeyValue("platformCode", spec.platformCode())
                .addKeyValue("endpointCode", spec.endpointCode())
                .addKeyValue("outcome", outcome.name())
                .addKeyValue("statusClass", (status / 100) + "xx")
                .addKeyValue("elapsedMillis", Duration.between(startedAt, clock.instant()).toMillis())
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Acquisition call completed");
        java.util.Map<String,String> metadata = new java.util.LinkedHashMap<>();
        response.headers().forEach((name,values) -> { if (values.size()==1) metadata.put(name,values.getFirst()); });
        return new AcquisitionResult(body, nativeStatus, outcome, null, complete, failureCode,
                status == 408 || status == 429 || status >= 500, null, null).withResponseHeaders(metadata);
    }

    /**
     * Report an answer that never reached a source, or one this adapter cannot
     * classify.
     *
     * <p>The result is deliberately an unclassified answer rather than an
     * exception. The caller's contract is to record what happened, and a refusal
     * that vanished as a stack trace would leave a gap where evidence should be.
     */
    private AcquisitionResult refused(String reason, Instant startedAt) {
        log.atWarn()
                .addKeyValue("event", "acquisition_call_refused")
                .addKeyValue("reason", reason)
                .addKeyValue("elapsedMillis", Duration.between(startedAt, clock.instant()).toMillis())
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Acquisition call refused before or during transport");
        return new AcquisitionResult(
                new byte[0],
                "MARKETOPS_REFUSED " + reason,
                AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE,
                null, false, reason,
                java.util.Set.of("transport_failed", "rate_limit_window_exhausted", "interrupted").contains(reason), null, null);
    }
}

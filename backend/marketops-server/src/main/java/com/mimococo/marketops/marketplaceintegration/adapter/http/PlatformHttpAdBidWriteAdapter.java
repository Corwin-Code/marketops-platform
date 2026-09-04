package com.mimococo.marketops.marketplaceintegration.adapter.http;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWritePort;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The one advertising write adapter, and it is data-driven.
 *
 * <p>There is no Ozon branch and no Wildberries branch, because there is no Ozon
 * or Wildberries knowledge in this class. Everything about how a bid write is
 * shaped — the endpoint, the method, the templates, the pointers, the auth
 * headers — comes from the verified registry, and a platform this product has
 * never been told about is simply a platform with no rows.
 *
 * <p>Five refusals happen before any socket is opened, and each one is the
 * reason an unverified Provider path is unreachable rather than merely
 * disabled:
 *
 * <ol>
 *   <li>the attempt this call belongs to is no longer current;</li>
 *   <li>no verified operation for this capability;</li>
 *   <li>no verified advertising auth header for this platform;</li>
 *   <li>a credential that cannot be resolved in this environment;</li>
 *   <li>a request or destination the recorded shape cannot produce.</li>
 * </ol>
 *
 * <p>The first is asked twice: once here, and once more after the destination
 * has been built and immediately before anything leaves. A kill switch that
 * arrives between those two moments still stops the call.
 *
 * <p>None of them throws. A refusal is an {@link AdBidWriteResult.Outcome},
 * because an exception across the port boundary would lose the distinction
 * between "we refused to call" and "we called and do not know what happened".
 */
public final class PlatformHttpAdBidWriteAdapter implements AdBidWritePort {

    /** The credential purpose an advertising write authenticates with. */
    private static final String ADS_WRITE = "ADS_WRITE";

    private final WriteOperationRepository operations;
    private final PlatformCallSpecRepository specs;
    private final SecretResolverPort secrets;
    private final OutboundHttp http;
    private final Clock clock;

    public PlatformHttpAdBidWriteAdapter(
            WriteOperationRepository operations,
            PlatformCallSpecRepository specs,
            SecretResolverPort secrets,
            OutboundHttp http,
            Clock clock) {
        this.operations = operations;
        this.specs = specs;
        this.secrets = secrets;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public AdBidWriteResult perform(AdBidWriteRequest request) {
        if (!specs.adBidAttemptCurrent(request)) {
            return AdBidWriteResult.refusedBeforeDispatch(
                    "attempt_authority_not_current", clock.instant());
        }
        Optional<WriteOperationSpec> found =
                operations.verifiedOperation(request.capabilityId(), request.operation().name());
        if (found.isEmpty()) {
            return AdBidWriteResult.refusedBeforeDispatch(
                    "write_operation_not_verified", clock.instant());
        }
        WriteOperationSpec operation = found.get();

        List<AuthHeaderSpec> authHeaders =
                specs.verifiedAuthHeaders(operation.platformCode(), ADS_WRITE);
        if (authHeaders.isEmpty()) {
            return AdBidWriteResult.refusedBeforeDispatch(
                    "authentication_not_recorded", clock.instant());
        }

        List<char[]> resolvedSecrets = new ArrayList<>();
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            for (AuthHeaderSpec header : authHeaders) {
                Optional<String> value = headerValue(header, request, resolvedSecrets);
                if (value.isEmpty()) {
                    return AdBidWriteResult.refusedBeforeDispatch(
                            "credential_unresolvable", clock.instant());
                }
                headers.put(header.headerName(), value.get());
            }

            Map<String, String> placeholders = placeholders(request);
            String path;
            String query;
            String body;
            try {
                path = RequestTemplate.render(operation.endpoint().pathTemplate(), placeholders,
                        RequestTemplate.Escaping.URL);
                query = RequestTemplate.render(operation.endpoint().queryTemplate(), placeholders,
                        RequestTemplate.Escaping.URL);
                body = RequestTemplate.render(operation.requestTemplate(), placeholders,
                        RequestTemplate.Escaping.JSON);
            } catch (RuntimeException templateRefused) {
                return AdBidWriteResult.refusedBeforeDispatch(
                        "request_could_not_be_built", clock.instant());
            }

            OutboundHttp.Destination destination = new OutboundHttp.Destination(
                    operation.platformCode() + ':' + operation.endpoint().endpointCode(),
                    java.net.URI.create(operation.endpoint().baseUrl() + path
                            + (query == null || query.isBlank() ? "" : "?" + query)),
                    operation.endpoint().httpMethod(),
                    headers.keySet(),
                    body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    operation.endpoint().requestTimeoutMillis(),
                    (int) Math.min(operation.endpoint().maxResponseBytes(), Integer.MAX_VALUE));

            OutboundHttp.Response response;
            try {
                OutboundHttp.Plan plan = http.prepare(destination);
                // Last question before the socket. Everything above this line is
                // preparation and can be discarded; everything below it may have
                // left the process.
                if (!specs.adBidAttemptCurrent(request)) {
                    return AdBidWriteResult.refusedBeforeDispatch(
                            "attempt_authority_not_current", clock.instant());
                }
                response = http.exchange(plan, headers);
            } catch (IllegalArgumentException destinationRefused) {
                // The outbound policy declined the destination. Nothing left, so
                // nothing happened, and this is a refusal rather than an unknown.
                return AdBidWriteResult.refusedBeforeDispatch(
                        "outbound_destination_refused", clock.instant());
            } catch (java.io.IOException | InterruptedException interrupted) {
                if (interrupted instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Something may have left. That is an unknown, never a refusal,
                // and the database will refuse to retry a mutating call from here.
                return new AdBidWriteResult(AdBidWriteResult.Outcome.UNKNOWN_STATE,
                        null, null, null, null, null, null, clock.instant(),
                        "provider_did_not_answer", null);
            }

            return classify(request, response);
        } finally {
            // Whatever happened, the resolved material does not outlive this call.
            resolvedSecrets.forEach(secret -> Arrays.fill(secret, '\0'));
        }
    }

    /**
     * Propose an outcome and hand back the exact bytes.
     *
     * <p>Deliberately shallow. The database re-classifies from the frozen
     * operation shape, so anything decided here is a hint; what matters is that
     * the bytes and the transport facts arrive intact and tied to the request
     * that produced them.
     */
    private AdBidWriteResult classify(AdBidWriteRequest request, OutboundHttp.Response response) {
        Map<String, String> retained = new HashMap<>();
        response.headers().forEach((name, values) -> {
            if (!values.isEmpty()) {
                retained.put(name.toLowerCase(Locale.ROOT), values.getFirst());
            }
        });
        AdBidWriteResult.Response transport;
        try {
            transport = new AdBidWriteResult.Response(response.statusCode(),
                    filterRetainable(retained), request.digest(), "PROVIDER_RESPONSE",
                    response.complete());
        } catch (IllegalArgumentException notEvidence) {
            return new AdBidWriteResult(AdBidWriteResult.Outcome.UNKNOWN_STATE,
                    null, null, null, null, null, null, clock.instant(),
                    "provider_evidence_missing_or_unbound", null);
        }
        AdBidWriteResult.Outcome proposed = response.complete() && response.statusCode() < 300
                ? AdBidWriteResult.Outcome.ACCEPTED
                : AdBidWriteResult.Outcome.UNKNOWN_STATE;
        return new AdBidWriteResult(proposed, String.valueOf(response.statusCode()), null,
                null, null, null, response.body(), clock.instant(), null, transport);
    }

    /** Only the headers the evidence record is allowed to keep. */
    private static Map<String, String> filterRetainable(Map<String, String> headers) {
        Map<String, String> retained = new LinkedHashMap<>();
        for (String name : List.of("content-type", "retry-after", "x-request-id",
                "etag", "x-version-id")) {
            String value = headers.get(name);
            if (value != null && value.length() <= 256) {
                retained.put(name, value);
            }
        }
        return retained;
    }

    /**
     * One header's value, resolving a secret only at the moment of use.
     *
     * <p>The resolved characters go into the list the caller zeroes, and nothing
     * that leaves this method carries them anywhere else.
     */
    private Optional<String> headerValue(
            AuthHeaderSpec header, AdBidWriteRequest request, List<char[]> resolvedSecrets) {
        return switch (header.valueSource()) {
            case LITERAL -> Optional.of(header.valueTemplate());
            case ACCOUNT_NATIVE_KEY -> specs.accountNativeKey(request.credentialId())
                    .map(key -> header.valueTemplate().replace("{value}", key));
            case RESOLVED_SECRET -> specs
                    .activeSecretReference(request.credentialId(), header.credentialPurpose())
                    .flatMap(secrets::resolve)
                    .map(secret -> {
                        resolvedSecrets.add(secret);
                        return header.valueTemplate().replace("{value}", new String(secret));
                    });
        };
    }

    /** The advertising write vocabulary, and nothing from the price one. */
    private static Map<String, String> placeholders(AdBidWriteRequest request) {
        Map<String, String> values = new HashMap<>();
        values.put("nativeCampaignKey", request.nativeCampaignKey());
        values.put("nativeObjectKey", request.nativeObjectKey());
        values.put("idempotencyKey", request.idempotencyKey());
        if (request.targetBid() != null) {
            values.put("targetBid", request.targetBid().amount().toPlainString());
            values.put("currencyCode", request.targetBid().currencyCode());
        }
        if (request.bidUnitCode() != null) {
            values.put("bidUnitCode", request.bidUnitCode());
        }
        if (request.nativeTaskKey() != null) {
            values.put("nativeTaskKey", request.nativeTaskKey());
        }
        return values;
    }
}

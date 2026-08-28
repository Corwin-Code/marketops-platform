package com.mimococo.marketops.marketplaceintegration.adapter.http;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.PriceWritePort;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import com.mimococo.marketops.shared.port.OutboundHttp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one outbound doorway for a price change, driven entirely by recorded
 * evidence.
 *
 * <p>Nothing about a marketplace is written here. Which endpoint performs the
 * apply, how the target price is placed in the request, whether the platform
 * answers with a result or with a handle, where that handle lives inside the
 * response and where an observed price lives in a readback are all recorded
 * facts carrying their own verification state. An operation nobody has verified
 * has no reachable specification, so the fail-closed behaviour is the absence of
 * a call.
 *
 * <p>This adapter classifies the answer and never concludes anything about the
 * command. An accepted apply means the platform took the request; whether the
 * platform holds the intended value is a question only a readback answers, and
 * only the command's own transition rules may draw the conclusion. Keeping that
 * separation here is what stops a marketplace's optimistic acknowledgement from
 * becoming a recorded success.
 *
 * <p>A timeout stays a timeout. It is the single most consequential answer in
 * this product, because the call may have changed a real price, and collapsing
 * it into a failure would authorize a retry that writes twice.
 */
public final class PlatformHttpPriceWriteAdapter implements PriceWritePort {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformHttpPriceWriteAdapter.class);

    private final OutboundHttp httpClient;
    private final WriteOperationRepository operations;
    private final PlatformCallSpecRepository specs;
    private final SecretResolverPort secrets;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final PriceWriteAnswers answers;

    public PlatformHttpPriceWriteAdapter(OutboundHttp httpClient,
                                         WriteOperationRepository operations,
                                         PlatformCallSpecRepository specs,
                                         SecretResolverPort secrets,
                                         ObjectMapper objectMapper,
                                         Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.specs = Objects.requireNonNull(specs, "specs");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.answers = new PriceWriteAnswers(this.clock);
    }

    @Override
    public PriceWriteResult perform(PriceWriteRequest request) {
        if (!specs.priceAttemptCurrent(request)) return refused("attempt_authority_not_current");
        Optional<WriteOperationSpec> found = operations.verifiedOperation(
                request.capabilityId(), request.operation().name());
        if (found.isEmpty()) {
            return refused("write_operation_not_verified");
        }
        WriteOperationSpec spec = found.get();
        if (!specs.reserveCallBudget(spec.endpoint().endpointId())) {
            return new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR, null, null, null,
                    null, new byte[0], clock.instant(), "rate_limit_window_exhausted");
        }

        List<AuthHeaderSpec> authHeaders = specs.verifiedAuthHeaders(spec.platformCode(), "PRICE_WRITE");
        if (authHeaders.isEmpty()) {
            return refused("authentication_not_recorded");
        }

        List<char[]> resolvedSecrets = new ArrayList<>();
        OutboundHttp.Request httpRequest;
        try {
            httpRequest = build(spec, request, authHeaders, resolvedSecrets);
        } catch (RuntimeException notBuildable) {
            return refused("request_could_not_be_built");
        } finally {
            resolvedSecrets.forEach(secret -> Arrays.fill(secret, '\0'));
        }
        if (httpRequest == null) {
            return refused("credential_unresolvable");
        }

        try {
            if (!specs.priceAttemptCurrent(request)) return refused("attempt_authority_not_current");
            OutboundHttp.Response response =
                    httpClient.exchange(httpRequest.plan(), httpRequest.headers());
            Map<String, String> safeHeaders = new HashMap<>();
            for (String name : List.of("content-type", "retry-after", "x-request-id", "etag", "x-version-id")) {
                response.firstHeader(name)
                        .filter(value -> value.length() <= 256
                                && value.chars().noneMatch(Character::isISOControl))
                        .ifPresent(value -> safeHeaders.put(name, value));
            }
            PriceWriteResult classified = response.complete() ? classify(response, spec, request)
                    : transportFailed(request, response.failureCode());
            return classified.withResponse(response.body(),
                    new PriceWriteResult.Response(response.statusCode(), safeHeaders,
                            request.digest(), "PROVIDER_RESPONSE", response.complete()));
        } catch (IOException transportFailure) {
            // For a write the call may have reached the marketplace and may
            // have changed a real price. That is precisely what an unknown
            // state means, and it is the reason there is no transition from it
            // back to executing. A read has no such consequence.
            return transportFailed(request, "transport_failed");
        } catch (IllegalArgumentException invalidPlan) {
            return transportFailed(request, "outbound_plan_rejected");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return transportFailed(request, "interrupted");
        }
    }

    private OutboundHttp.Request build(WriteOperationSpec spec,
                              PriceWriteRequest request,
                              List<AuthHeaderSpec> authHeaders,
                              List<char[]> resolvedSecrets) {
        EndpointCallSpec endpoint = spec.endpoint();
        Map<String, String> placeholders = placeholders(request);
        String path = RequestTemplate.render(endpoint.pathTemplate(), placeholders,
                RequestTemplate.Escaping.URL);
        String query = RequestTemplate.render(endpoint.queryTemplate(), placeholders,
                RequestTemplate.Escaping.URL);
        String body = RequestTemplate.render(spec.requestTemplate(), placeholders,
                RequestTemplate.Escaping.JSON);
        if (body != null && body.isEmpty()) body = null;
        if (body != null && !com.mimococo.marketops.shared.JsonValues.read(objectMapper, body).isObject()) {
            throw new IllegalArgumentException("write operation body must be a JSON object");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", endpoint.responseContentType() == null ? "application/json" : endpoint.responseContentType());
        if (body != null) headers.put("Content-Type", "application/json");
        if (request.operation() == PriceWriteRequest.Operation.RESTORE) {
            if (spec.conditionalWriteHeader() == null || request.expectedVersionToken() == null) {
                throw new IllegalArgumentException("restore has no verified conditional precondition");
            }
            headers.put(spec.conditionalWriteHeader(), request.expectedVersionToken());
        }
        java.util.Set<String> names = new java.util.HashSet<>(headers.keySet());
        authHeaders.forEach(header -> { names.add(header.headerName()); OutboundHttp.requireHeaderTemplate(header.valueTemplate()); });
        headers.values().forEach(OutboundHttp::requireHeaderTemplate);
        OutboundHttp.Plan plan = httpClient.prepare(new OutboundHttp.Destination(
                "platform:" + spec.platformCode() + ":write",
                URI.create(endpoint.baseUrl() + path + (query == null ? "" : "?" + query)), endpoint.httpMethod(), names,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                endpoint.requestTimeoutMillis(), Math.toIntExact(endpoint.maxResponseBytes())));

        if (!specs.priceAttemptCurrent(request)) throw new IllegalArgumentException("call authority expired before credential resolution");

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
                                         PriceWriteRequest request,
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

    /**
     * The values a recorded template may place.
     *
     * <p>The idempotency key is among them because a platform that supports one
     * expects it in the request rather than in a header of this product's
     * choosing, and which it is remains the marketplace's fact.
     */
    private static Map<String, String> placeholders(PriceWriteRequest request) {
        Map<String, String> values = new HashMap<>();
        values.put("nativeListingKey",
                request.nativeListingKey() == null ? "" : request.nativeListingKey());
        values.put("nativeVariantKey",
                request.nativeVariantKey() == null ? "" : request.nativeVariantKey());
        values.put("targetPrice", request.targetPrice().amount().toPlainString());
        values.put("currencyCode", request.targetPrice().currencyCode());
        values.put("idempotencyKey",
                request.idempotencyKey() == null ? "" : request.idempotencyKey());
        values.put("nativeTaskKey",
                request.nativeTaskKey() == null ? "" : request.nativeTaskKey());
        return values;
    }

    /**
     * Classify one answer from its transport status and the recorded pointers.
     *
     * <p>The status rules are HTTP semantics, which can be applied without
     * inventing anything. Everything platform-specific — where a task key lives,
     * what the platform's own word for success is, where an observed price sits
     * — comes from the recorded specification, so a marketplace that words its
     * answers differently is a row somebody edits rather than a branch somebody
     * writes.
     */
    private PriceWriteResult classify(OutboundHttp.Response response,
                                      WriteOperationSpec spec,
                                      PriceWriteRequest request) {
        int status = response.statusCode();
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (body.length > spec.endpoint().maxResponseBytes()) {
            return answers.unknown("response_exceeded_recorded_bound");
        }
        String nativeStatus = "HTTP " + status;

        logCompleted(spec, status);

        if (status < 200 || status >= 300) {
            return answers.inconclusive(request, status, nativeStatus, body);
        }

        JsonNode document;
        try {
            document = com.mimococo.marketops.shared.JsonValues.read(objectMapper,body);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            return answers.unknown("response_not_readable");
        }

        return switch (request.operation()) {
            case APPLY, RESTORE -> answers.applied(spec, document, nativeStatus, body);
            case STATUS_ENQUIRY -> answers.enquired(spec, document, body,
                    request);
            case READBACK -> answers.observed(spec, document, nativeStatus, body);
        };
    }

    private PriceWriteResult refused(String errorCode) {
        log.atWarn()
                .addKeyValue("event", "price_write_call_refused")
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A price write was refused before any call was made");
        return new PriceWriteResult(PriceWriteResult.Outcome.REJECTED, null, null, null, null,
                new byte[0], clock.instant(), errorCode);
    }

    /** A call that did not complete, classified by whether it could have written. */
    private PriceWriteResult transportFailed(PriceWriteRequest request, String errorCode) {
        boolean mutating = request.operation() == PriceWriteRequest.Operation.APPLY
                || request.operation() == PriceWriteRequest.Operation.RESTORE;
        if (!mutating) {
            log.atWarn()
                    .addKeyValue("event", "price_write_read_failed")
                    .addKeyValue("errorCode", errorCode)
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("A price readback or status enquiry did not complete");
            return new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR, null, null,
                    null, null, new byte[0], clock.instant(), errorCode);
        }
        return answers.unknown(errorCode);
    }


    private void logCompleted(WriteOperationSpec spec, int status) {
        log.atInfo()
                .addKeyValue("event", "price_write_call_completed")
                .addKeyValue("platformCode", spec.platformCode())
                .addKeyValue("operation", spec.operation())
                .addKeyValue("statusClass", (status / 100) + "xx")
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Price write call completed");
    }
}

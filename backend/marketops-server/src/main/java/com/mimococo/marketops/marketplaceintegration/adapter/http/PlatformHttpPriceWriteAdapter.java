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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private final HttpClient httpClient;
    private final WriteOperationRepository operations;
    private final PlatformCallSpecRepository specs;
    private final SecretResolverPort secrets;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlatformHttpPriceWriteAdapter(HttpClient httpClient,
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
    }

    @Override
    public PriceWriteResult perform(PriceWriteRequest request) {
        Optional<WriteOperationSpec> found = operations.verifiedOperation(
                request.capabilityId(), request.operation().name());
        if (found.isEmpty()) {
            return refused("write_operation_not_verified");
        }
        WriteOperationSpec spec = found.get();

        List<AuthHeaderSpec> authHeaders = specs.verifiedAuthHeaders(spec.platformCode());
        if (authHeaders.isEmpty()) {
            return refused("authentication_not_recorded");
        }

        List<char[]> resolvedSecrets = new ArrayList<>();
        HttpRequest httpRequest;
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
            HttpResponse<byte[]> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            return classify(response, spec, request);
        } catch (IOException transportFailure) {
            // For a write the call may have reached the marketplace and may
            // have changed a real price. That is precisely what an unknown
            // state means, and it is the reason there is no transition from it
            // back to executing. A read has no such consequence.
            return transportFailed(request, "transport_failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return transportFailed(request, "interrupted");
        }
    }

    private HttpRequest build(WriteOperationSpec spec,
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

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.baseUrl() + path + (query == null ? "" : "?" + query)))
                .timeout(Duration.ofMillis(endpoint.requestTimeoutMillis()))
                .header("Accept", endpoint.responseContentType() == null
                        ? "application/json" : endpoint.responseContentType())
                .method(endpoint.httpMethod(), body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        for (AuthHeaderSpec header : authHeaders) {
            Optional<String> value = headerValue(header, request, resolvedSecrets);
            if (value.isEmpty()) {
                return null;
            }
            builder.header(header.headerName(), value.get());
        }
        return builder.build();
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
    private PriceWriteResult classify(HttpResponse<byte[]> response,
                                      WriteOperationSpec spec,
                                      PriceWriteRequest request) {
        int status = response.statusCode();
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (body.length > spec.endpoint().maxResponseBytes()) {
            return unknown("response_exceeded_recorded_bound");
        }
        String nativeStatus = "HTTP " + status;

        logCompleted(spec, status);

        if (status < 200 || status >= 300) {
            return inconclusive(request, status, nativeStatus, body);
        }

        JsonNode document;
        try {
            document = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JacksonException unreadable) {
            return unknown("response_not_readable");
        }

        return switch (request.operation()) {
            case APPLY, RESTORE -> applied(spec, document, nativeStatus, body);
            case STATUS_ENQUIRY -> enquired(spec, document, nativeStatus, body, request);
            case READBACK -> observed(spec, document, nativeStatus, body);
        };
    }

    /**
     * Classify an answer that was not a success.
     *
     * <p>The distinction that matters is whether the call could have taken
     * effect. A rate-limit refusal is the one status where HTTP genuinely says
     * the request was not processed, so it is safely retriable for anything. A
     * timeout or a server failure says nothing of the kind: for a read that is
     * merely inconvenient and worth retrying, but for a write the request may
     * have reached the marketplace and changed a real price, so the honest
     * answer is that the outcome is unknown.
     *
     * <p>This is HTTP semantics rather than a marketplace fact, which is why it
     * can be decided here. What each platform does with a request it refuses is
     * recorded elsewhere; what an unanswered write means is universal.
     */
    private PriceWriteResult inconclusive(PriceWriteRequest request, int status,
                                          String nativeStatus, byte[] body) {
        boolean mutating = request.operation() == PriceWriteRequest.Operation.APPLY
                || request.operation() == PriceWriteRequest.Operation.RESTORE;
        if (status == 429) {
            return new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR, nativeStatus,
                    null, null, null, body, clock.instant(), "platform_rate_limited");
        }
        if (status == 408 || status >= 500) {
            if (mutating) {
                return new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE,
                        nativeStatus, null, null, null, body, clock.instant(),
                        "platform_did_not_answer_a_write");
            }
            return new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR, nativeStatus,
                    null, null, null, body, clock.instant(), "platform_unavailable");
        }
        return new PriceWriteResult(PriceWriteResult.Outcome.REJECTED, nativeStatus, null, null,
                null, body, clock.instant(), "platform_rejected");
    }

    /**
     * What an apply answered.
     *
     * <p>An asynchronous platform answers with a handle, and the absence of that
     * handle where one is expected is an unknown state rather than a success:
     * the platform accepted something, but nothing here can later ask what
     * became of it.
     */
    private PriceWriteResult applied(WriteOperationSpec spec, JsonNode document,
                                     String nativeStatus, byte[] body) {
        if (!spec.asynchronous()) {
            return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, nativeStatus, null,
                    null, null, body, clock.instant(), null);
        }
        String taskKey = text(document, spec.taskKeyPointer());
        if (taskKey == null) {
            return unknown("task_key_not_at_recorded_pointer");
        }
        return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, nativeStatus, taskKey,
                null, null, body, clock.instant(), null);
    }

    /**
     * What a status enquiry answered.
     *
     * <p>The platform's own words for finished and rejected are recorded values,
     * compared literally. Anything else means the work is still running, which
     * is a retriable condition rather than an outcome.
     */
    private PriceWriteResult enquired(WriteOperationSpec spec, JsonNode document,
                                      String nativeStatus, byte[] body,
                                      PriceWriteRequest request) {
        String taskStatus = text(document, spec.taskStatusPointer());
        if (taskStatus == null) {
            return unknown("task_status_not_at_recorded_pointer");
        }
        if (taskStatus.equals(spec.taskSuccessValue())) {
            return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, taskStatus, null,
                    null, null, body, clock.instant(), null);
        }
        if (taskStatus.equals(spec.taskFailureValue())) {
            return new PriceWriteResult(PriceWriteResult.Outcome.REJECTED, taskStatus, null,
                    null, null, body, clock.instant(), "platform_task_rejected");
        }
        return new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR, taskStatus,
                request.nativeTaskKey(), null, null, body, clock.instant(),
                "platform_task_in_progress");
    }

    /**
     * What a readback observed.
     *
     * <p>A response the recorded pointer does not reach carries no price. That is
     * reported as an unreadable observation rather than as a mismatch, because
     * "the platform holds something else" and "this product could not tell what
     * the platform holds" lead an operator to different actions.
     */
    private PriceWriteResult observed(WriteOperationSpec spec, JsonNode document,
                                      String nativeStatus, byte[] body) {
        String price = text(document, spec.observedPricePointer());
        if (price == null) {
            return unknown("observed_price_not_at_recorded_pointer");
        }
        BigDecimal observed;
        try {
            observed = new BigDecimal(price);
        } catch (NumberFormatException notANumber) {
            return unknown("observed_price_not_a_number");
        }
        String currency = spec.observedCurrencyPointer() == null
                ? null : text(document, spec.observedCurrencyPointer());
        return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, nativeStatus, null,
                observed, currency, body, clock.instant(), null);
    }

    /**
     * Read one recorded pointer as text.
     *
     * <p>Numbers are accepted as well as strings, because a marketplace that
     * reports a price as a JSON number is not wrong, and refusing it would make
     * the pointer mechanism useless for exactly the field it exists for.
     */
    private static String text(JsonNode document, String pointer) {
        if (pointer == null) {
            return null;
        }
        JsonNode node = document.at(pointer);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            String value = node.asString().trim();
            return value.isEmpty() ? null : value;
        }
        return node.isNumber() || node.isBoolean() ? node.asString() : null;
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
        return unknown(errorCode);
    }

    private PriceWriteResult unknown(String errorCode) {
        log.atWarn()
                .addKeyValue("event", "price_write_outcome_unknown")
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A price write left an outcome that cannot be classified");
        return new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE, null, null, null,
                null, new byte[0], clock.instant(), errorCode);
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

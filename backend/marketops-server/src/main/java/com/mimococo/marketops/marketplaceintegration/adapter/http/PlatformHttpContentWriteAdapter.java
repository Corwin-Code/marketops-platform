package com.mimococo.marketops.marketplaceintegration.adapter.http;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ContentOperationSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentOperationRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.port.ContentWritePort;
import com.mimococo.marketops.marketplaceintegration.port.ContentWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.ContentWriteResult;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.JsonValues;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one outbound doorway a listing title and description change leaves through (W2), driven
 * entirely by recorded, verified evidence.
 *
 * <p>Nothing about a marketplace is written here. Which endpoint writes, how the article, the
 * title and the description are placed in the request, where the platform task key lives in the
 * answer, which task statuses mean finished, failed or still pending, and where a readback holds
 * the title and the description are all recorded facts with their own verification state. An
 * operation nobody verified has no reachable specification, so the fail-closed behaviour is the
 * absence of a call.
 *
 * <p>An apply that did not get a complete answer stays unknown: the card may have changed, and
 * calling that a failure would invite a second write. Reads have no such consequence and may be
 * asked again.
 */
public final class PlatformHttpContentWriteAdapter implements ContentWritePort {

    private static final Logger log = LoggerFactory.getLogger(PlatformHttpContentWriteAdapter.class);

    /** The credential purpose a content write authenticates with. */
    private static final String CONTENT_WRITE = "CONTENT_WRITE";

    /** Answer headers kept with the record: none of them carries a credential. */
    private static final List<String> SAFE_HEADERS = List.of("content-type", "retry-after",
            "item-retry-after", "item-rate-limit-remaining", "x-request-id");

    /** The longest failure detail kept from an answer. */
    private static final int DETAIL_LIMIT = 2000;

    private final OutboundHttp httpClient;
    private final ContentOperationRepository operations;
    private final PlatformCallSpecRepository specs;
    private final SecretResolverPort secrets;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlatformHttpContentWriteAdapter(OutboundHttp httpClient,
                                           ContentOperationRepository operations,
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
    public ContentWriteResult perform(ContentWriteRequest request) {
        Optional<ContentOperationSpec> found = operations.verifiedOperation(
                request.capabilityId(), request.operation().name());
        if (found.isEmpty()) {
            return refused("write_operation_not_verified");
        }
        ContentOperationSpec spec = found.get();
        if (request.credentialId() == null) {
            return refused("credential_unresolvable");
        }
        if (!specs.reserveCallBudget(spec.endpoint().endpointId())) {
            return result(ContentWriteResult.Outcome.RETRIABLE_ERROR, false, null)
                    .errorCode("rate_limit_window_exhausted").build();
        }
        List<AuthHeaderSpec> authHeaders = specs.verifiedAuthHeaders(spec.platformCode(), CONTENT_WRITE);
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
            OutboundHttp.Response response = httpClient.exchange(httpRequest.plan(), httpRequest.headers());
            Map<String, String> safeHeaders = new HashMap<>();
            for (String name : SAFE_HEADERS) {
                response.firstHeader(name)
                        .filter(value -> value.length() <= 256 && value.chars().noneMatch(Character::isISOControl))
                        .ifPresent(value -> safeHeaders.put(name, value));
            }
            if (!response.complete()) {
                return transportFailed(request, response.failureCode());
            }
            logCompleted(spec, response.statusCode());
            return classify(response.statusCode(), response.body(), safeHeaders, spec, request);
        } catch (IOException transportFailure) {
            return transportFailed(request, "transport_failed");
        } catch (IllegalArgumentException invalidPlan) {
            return transportFailed(request, "outbound_plan_rejected");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return transportFailed(request, "interrupted");
        }
    }

    private OutboundHttp.Request build(ContentOperationSpec spec, ContentWriteRequest request,
                                       List<AuthHeaderSpec> authHeaders, List<char[]> resolvedSecrets) {
        EndpointCallSpec endpoint = spec.endpoint();
        Map<String, String> placeholders = placeholders(request);
        String path = RequestTemplate.render(endpoint.pathTemplate(), placeholders, RequestTemplate.Escaping.URL);
        String query = RequestTemplate.render(endpoint.queryTemplate(), placeholders, RequestTemplate.Escaping.URL);
        String body = RequestTemplate.render(spec.requestTemplate(), placeholders, RequestTemplate.Escaping.JSON);
        if (body != null && body.isEmpty()) {
            body = null;
        }
        if (body != null && !JsonValues.read(objectMapper, body).isObject()) {
            throw new IllegalArgumentException("content operation body must be a JSON object");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", endpoint.responseContentType() == null ? "application/json"
                : endpoint.responseContentType());
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        Set<String> names = new HashSet<>(headers.keySet());
        authHeaders.forEach(header -> {
            names.add(header.headerName());
            OutboundHttp.requireHeaderTemplate(header.valueTemplate());
        });
        headers.values().forEach(OutboundHttp::requireHeaderTemplate);
        // The same outbound rule key the price write uses: one exact allow rule per write path.
        OutboundHttp.Plan plan = httpClient.prepare(new OutboundHttp.Destination(
                "platform:" + spec.platformCode() + ":write",
                URI.create(endpoint.baseUrl() + path + (query == null ? "" : "?" + query)),
                endpoint.httpMethod(), names,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                endpoint.requestTimeoutMillis(), Math.toIntExact(endpoint.maxResponseBytes())));

        for (AuthHeaderSpec header : authHeaders) {
            Optional<String> value = headerValue(header, request, resolvedSecrets);
            if (value.isEmpty()) {
                return null;
            }
            headers.put(header.headerName(), value.get());
        }
        return new OutboundHttp.Request(plan, headers);
    }

    private Optional<String> headerValue(AuthHeaderSpec header, ContentWriteRequest request,
                                         List<char[]> resolvedSecrets) {
        return switch (header.valueSource()) {
            case LITERAL -> Optional.of(header.valueTemplate());
            case ACCOUNT_NATIVE_KEY -> specs.accountNativeKey(request.credentialId())
                    .map(key -> header.valueTemplate().replace("{value}", key));
            case RESOLVED_SECRET -> {
                Optional<String> reference = specs.activeSecretReference(request.credentialId(),
                        header.credentialPurpose());
                if (reference.isEmpty()) {
                    yield Optional.empty();
                }
                Optional<char[]> secret = secrets.resolve(reference.get());
                if (secret.isEmpty()) {
                    yield Optional.empty();
                }
                resolvedSecrets.add(secret.get());
                yield Optional.of(header.valueTemplate().replace("{value}", new String(secret.get())));
            }
        };
    }

    /** The values a recorded content template may place. */
    private static Map<String, String> placeholders(ContentWriteRequest request) {
        Map<String, String> values = new HashMap<>();
        values.put("offerKey", request.offerKey());
        values.put("titleText", request.titleText() == null ? "" : request.titleText());
        values.put("descriptionText", request.descriptionText() == null ? "" : request.descriptionText());
        values.put("nativeTaskKey", request.nativeTaskKey() == null ? "" : request.nativeTaskKey());
        return values;
    }

    /**
     * Classify one complete answer from its status and the recorded pointers.
     *
     * <p>The status rules are HTTP semantics; everything platform-specific comes from the record.
     */
    private ContentWriteResult classify(int status, byte[] rawBody, Map<String, String> headers,
                                        ContentOperationSpec spec, ContentWriteRequest request) {
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        boolean apply = request.operation() == ContentWriteRequest.Operation.APPLY;
        if (body.length > spec.endpoint().maxResponseBytes()) {
            return result(apply ? ContentWriteResult.Outcome.UNKNOWN_STATE
                    : ContentWriteResult.Outcome.UNREADABLE, true, status)
                    .errorCode("response_exceeded_recorded_bound").body(body).build();
        }
        if (status == 429) {
            return result(ContentWriteResult.Outcome.RETRIABLE_ERROR, true, status)
                    .errorCode("platform_rate_limited").retryAfter(retryAfterSeconds(headers))
                    .detail(message(body)).body(body).build();
        }
        if (status < 200 || status >= 300) {
            if (!apply) {
                return result(ContentWriteResult.Outcome.RETRIABLE_ERROR, true, status)
                        .errorCode("read_answered_" + status).detail(message(body)).body(body).build();
            }
            // A server error may have come after the card changed; a client error is a refusal.
            return result(status >= 500 ? ContentWriteResult.Outcome.UNKNOWN_STATE
                    : ContentWriteResult.Outcome.REJECTED, true, status)
                    .errorCode(status >= 500 ? "platform_error_after_dispatch" : "platform_rejected")
                    .detail(message(body)).body(body).build();
        }

        JsonNode document;
        try {
            document = JsonValues.read(objectMapper, body);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            return result(apply ? ContentWriteResult.Outcome.UNKNOWN_STATE
                    : ContentWriteResult.Outcome.UNREADABLE, true, status)
                    .errorCode("response_not_readable").body(body).build();
        }
        return switch (request.operation()) {
            case APPLY -> applied(spec, document, status, body);
            case STATUS_ENQUIRY -> enquired(spec, document, status, body);
            case READBACK -> observed(spec, document, status, body);
        };
    }

    /** An apply is taken when its answer names the platform task opened for it. */
    private ContentWriteResult applied(ContentOperationSpec spec, JsonNode document, int status, byte[] body) {
        String taskKey = scalar(at(document, spec.taskKeyPointer()));
        if (taskKey == null || taskKey.isBlank() || taskKey.length() > 64
                || taskKey.chars().anyMatch(Character::isISOControl)) {
            return result(ContentWriteResult.Outcome.UNKNOWN_STATE, true, status)
                    .errorCode("task_key_missing").body(body).build();
        }
        return result(ContentWriteResult.Outcome.ACCEPTED, true, status).taskKey(taskKey).body(body).build();
    }

    /** What the platform says about the task, in its own words. */
    private ContentWriteResult enquired(ContentOperationSpec spec, JsonNode document, int status, byte[] body) {
        String taskStatus = scalar(at(document, spec.taskStatusPointer()));
        if (taskStatus == null) {
            return result(ContentWriteResult.Outcome.UNREADABLE, true, status)
                    .errorCode("task_status_missing").body(body).build();
        }
        ContentWriteResult.Outcome outcome;
        if (taskStatus.equals(spec.taskSuccessValue()) || spec.noChangeValues().contains(taskStatus)) {
            outcome = ContentWriteResult.Outcome.TASK_SUCCEEDED;
        } else if (taskStatus.equals(spec.taskFailureValue())) {
            outcome = ContentWriteResult.Outcome.TASK_FAILED;
        } else if (spec.taskPendingValues().contains(taskStatus)) {
            outcome = ContentWriteResult.Outcome.TASK_PENDING;
        } else {
            return result(ContentWriteResult.Outcome.UNREADABLE, true, status).taskStatus(bounded(taskStatus))
                    .errorCode("task_status_unrecognised").body(body).build();
        }
        JsonNode errors = at(document, spec.errorsPointer());
        String detail = errors == null || errors.isMissingNode() || errors.isNull()
                || (errors.isArray() && errors.isEmpty()) ? null : bounded(errors.toString());
        return result(outcome, true, status).taskStatus(bounded(taskStatus)).detail(detail).body(body).build();
    }

    /** The title and the description as the marketplace holds them. */
    private ContentWriteResult observed(ContentOperationSpec spec, JsonNode document, int status, byte[] body) {
        JsonNode title = at(document, spec.titlePointer());
        JsonNode description = at(document, spec.descriptionPointer());
        if (title == null || title.isMissingNode() || !title.isString()
                || description == null || description.isMissingNode()
                || !(description.isString() || description.isNull())) {
            return result(ContentWriteResult.Outcome.UNREADABLE, true, status)
                    .errorCode("readback_fields_missing").body(body).build();
        }
        return result(ContentWriteResult.Outcome.OBSERVED, true, status)
                .observed(title.asString(), description.isNull() ? "" : description.asString())
                .body(body).build();
    }

    private static JsonNode at(JsonNode document, String pointer) {
        if (pointer == null || pointer.isBlank()) {
            return null;
        }
        return document.at(pointer);
    }

    /** A string or a number at a pointer, as text; anything else is absent. */
    private static String scalar(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue().toString();
        }
        return null;
    }

    /** The platform's own message about a refusal, when its answer carries one. */
    private String message(byte[] body) {
        try {
            JsonNode document = JsonValues.read(objectMapper, body);
            JsonNode message = document.get("message");
            if (message != null && message.isString()) {
                return bounded(message.asString());
            }
            return bounded(document.toString());
        } catch (RuntimeException unreadable) {
            return body.length == 0 ? null : bounded(new String(body, StandardCharsets.UTF_8));
        }
    }

    /**
     * How long the platform asked to wait. Ozon states Item-Retry-After in minutes for the
     * product-update limits; a plain Retry-After is in seconds.
     */
    private static Integer retryAfterSeconds(Map<String, String> headers) {
        Integer minutes = positiveInteger(headers.get("item-retry-after"));
        if (minutes != null) {
            return Math.min(minutes, 24 * 60) * 60;
        }
        Integer seconds = positiveInteger(headers.get("retry-after"));
        return seconds == null ? null : Math.min(seconds, 24 * 60 * 60);
    }

    private static Integer positiveInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }

    private static String bounded(String text) {
        if (text == null) {
            return null;
        }
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        return clean.length() <= DETAIL_LIMIT ? clean : clean.substring(0, DETAIL_LIMIT);
    }

    private ContentWriteResult refused(String errorCode) {
        log.atWarn()
                .addKeyValue("event", "content_write_call_refused")
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A content write was refused before any call was made");
        return result(ContentWriteResult.Outcome.REJECTED, false, null).errorCode(errorCode).build();
    }

    /** A call that did not complete, classified by whether it could have written. */
    private ContentWriteResult transportFailed(ContentWriteRequest request, String errorCode) {
        return result(request.mutating() ? ContentWriteResult.Outcome.UNKNOWN_STATE
                : ContentWriteResult.Outcome.RETRIABLE_ERROR, true, null).errorCode(errorCode).build();
    }

    private void logCompleted(ContentOperationSpec spec, int status) {
        log.atInfo()
                .addKeyValue("event", "content_write_call_completed")
                .addKeyValue("platformCode", spec.platformCode())
                .addKeyValue("operation", spec.operation())
                .addKeyValue("statusClass", (status / 100) + "xx")
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Content write call completed");
    }

    private Builder result(ContentWriteResult.Outcome outcome, boolean dispatched, Integer status) {
        return new Builder(outcome, dispatched, status);
    }

    /** Assembles one result; every field not named stays absent. */
    private final class Builder {
        private final ContentWriteResult.Outcome outcome;
        private final boolean dispatched;
        private final Integer status;
        private String taskKey;
        private String taskStatus;
        private String observedTitle;
        private String observedDescription;
        private String errorCode;
        private String detail;
        private Integer retryAfter;
        private byte[] body = new byte[0];

        private Builder(ContentWriteResult.Outcome outcome, boolean dispatched, Integer status) {
            this.outcome = outcome;
            this.dispatched = dispatched;
            this.status = status;
        }

        Builder taskKey(String value) { taskKey = value; return this; }
        Builder taskStatus(String value) { taskStatus = value; return this; }
        Builder observed(String title, String description) {
            observedTitle = title;
            observedDescription = description;
            return this;
        }
        Builder errorCode(String value) { errorCode = value; return this; }
        Builder detail(String value) { detail = value; return this; }
        Builder retryAfter(Integer value) { retryAfter = value; return this; }
        Builder body(byte[] value) { body = value == null ? new byte[0] : value; return this; }

        ContentWriteResult build() {
            return new ContentWriteResult(outcome, dispatched, status, taskKey, taskStatus, observedTitle,
                    observedDescription, errorCode, detail, retryAfter, body, clock.instant());
        }
    }
}

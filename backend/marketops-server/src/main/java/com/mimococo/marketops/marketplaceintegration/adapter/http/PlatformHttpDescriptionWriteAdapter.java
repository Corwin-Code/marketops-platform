package com.mimococo.marketops.marketplaceintegration.adapter.http;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.DescriptionChangeGuard;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RetryAfterUnits;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWritePort;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteResult;
import com.mimococo.marketops.shared.JsonValues;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one description write adapter, and it is data-driven.
 *
 * <p>No Ozon branch and no Wildberries branch. Everything about how a
 * description write is shaped comes from the verified registry, and a platform
 * this product has never been told about is a platform with no rows.
 *
 * <p>Six refusals happen before any socket is opened:
 *
 * <ol>
 *   <li>the attempt this call belongs to is no longer current;</li>
 *   <li>no verified operation for this capability;</li>
 *   <li>no verified content-write auth header for this platform;</li>
 *   <li>a credential that cannot be resolved in this environment;</li>
 *   <li>a request or destination the recorded shape cannot produce;</li>
 *   <li>a rendered body the description guard refuses: a non-target field, a
 *       missing or forged marking declaration, a text outside the verified
 *       bound, or a whole-card shape.</li>
 * </ol>
 *
 * <p>The first is asked twice, the second time immediately before anything
 * leaves. None of them throws.
 */
public final class PlatformHttpDescriptionWriteAdapter implements DescriptionWritePort {

    private static final String CONTENT_WRITE = "CONTENT_WRITE";

    private final WriteOperationRepository operations;
    private final PlatformCallSpecRepository specs;
    private final SecretResolverPort secrets;
    private final OutboundHttp http;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public PlatformHttpDescriptionWriteAdapter(WriteOperationRepository operations,
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
    public DescriptionWriteResult perform(DescriptionWriteRequest request) {
        PlatformCallSpecRepository.DescriptionAttemptContext context =
                specs.descriptionAttemptContext(request).orElse(null);
        if (context == null) {
            return DescriptionWriteResult.refusedBeforeDispatch(
                    "attempt_authority_not_current", clock.instant());
        }
        Optional<WriteOperationSpec> found =
                operations.verifiedOperation(request.capabilityId(), request.operation().name());
        if (found.isEmpty()) {
            return DescriptionWriteResult.refusedBeforeDispatch(
                    "write_operation_not_verified", clock.instant());
        }
        WriteOperationSpec operation = found.get();
        List<AuthHeaderSpec> authHeaders =
                specs.verifiedAuthHeaders(operation.platformCode(), CONTENT_WRITE);
        if (authHeaders.isEmpty()) {
            return DescriptionWriteResult.refusedBeforeDispatch(
                    "authentication_not_recorded", clock.instant());
        }

        List<char[]> resolvedSecrets = new ArrayList<>();
        try {
            boolean mutating = request.operation() == DescriptionWriteRequest.Operation.APPLY
                    || request.operation() == DescriptionWriteRequest.Operation.RESTORE;
            if (mutating && (operation.descriptionAttributeKey() == null
                    || !operation.descriptionAttributeKey().equals(request.descriptionAttributeKey()))) {
                return DescriptionWriteResult.refusedBeforeDispatch(
                        "description_attribute_unverified", clock.instant());
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
                return DescriptionWriteResult.refusedBeforeDispatch(
                        "request_could_not_be_built", clock.instant());
            }
            if (mutating) {
                JsonNode rendered;
                try {
                    rendered = body == null ? null : JsonValues.read(mapper, body);
                } catch (RuntimeException notJson) {
                    rendered = null;
                }
                Optional<String> refusal = DescriptionChangeGuard.refusal(rendered,
                        operation.descriptionRequestGuard(), new DescriptionChangeGuard.Expected(
                                request.nativeListingKey(), request.descriptionAttributeKey(), request.descriptionText(),
                                request.kizMarkedDeclared(), context.lengthBoundMin(), context.lengthBoundMax()));
                if (refusal.isPresent()) {
                    return DescriptionWriteResult.refusedBeforeDispatch(refusal.get(), clock.instant());
                }
            }

            String conditionalHeader = operation.conditionalWriteHeader();
            String version = request.expectedVersionToken();
            boolean conditional = mutating && (conditionalHeader != null || version != null
                    || request.operation() == DescriptionWriteRequest.Operation.RESTORE);
            if ((!mutating && version != null) || (conditional && !validPrecondition(conditionalHeader, version))) {
                return DescriptionWriteResult.refusedBeforeDispatch("conditional_write_unbound", clock.instant());
            }
            var headerNames = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            if (body != null) headerNames.add("Content-Type");
            for (AuthHeaderSpec header : authHeaders) {
                if (!headerNames.add(header.headerName())) {
                    return DescriptionWriteResult.refusedBeforeDispatch("duplicate_auth_header", clock.instant());
                }
            }
            if (conditional && !headerNames.add(conditionalHeader)) {
                return DescriptionWriteResult.refusedBeforeDispatch("conditional_header_collision", clock.instant());
            }

            OutboundHttp.Destination destination = new OutboundHttp.Destination(
                    operation.platformCode() + ':' + operation.endpoint().endpointCode(),
                    java.net.URI.create(operation.endpoint().baseUrl() + path
                            + (query == null || query.isBlank() ? "" : "?" + query)),
                    operation.endpoint().httpMethod(),
                    headerNames,
                    body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                    operation.endpoint().requestTimeoutMillis(),
                    (int) Math.min(operation.endpoint().maxResponseBytes(), Integer.MAX_VALUE));

            OutboundHttp.Response response;
            try {
                OutboundHttp.Plan plan = http.prepare(destination);
                if (context.queryBindingRequired() && !specs.recordDescriptionTaskQuery(request,destination.body())) {
                    return DescriptionWriteResult.refusedBeforeDispatch("exact_task_query_not_bound", clock.instant());
                }
                Map<String, String> headers = new LinkedHashMap<>();
                if (body != null) headers.put("Content-Type", "application/json");
                for (AuthHeaderSpec header : authHeaders) {
                    Optional<String> value = headerValue(header, request, resolvedSecrets);
                    if (value.isEmpty()) {
                        return DescriptionWriteResult.refusedBeforeDispatch("credential_unresolvable", clock.instant());
                    }
                    OutboundHttp.requireHeaderTemplate(value.get());
                    headers.put(header.headerName(), value.get());
                }
                if (conditional) headers.put(conditionalHeader, version);
                if (specs.descriptionAttemptContext(request).isEmpty()) {
                    return DescriptionWriteResult.refusedBeforeDispatch(
                            "attempt_authority_not_current", clock.instant());
                }
                response = http.exchange(plan, headers);
            } catch (java.io.IOException | InterruptedException interrupted) {
                if (interrupted instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return new DescriptionWriteResult(DescriptionWriteResult.Outcome.UNKNOWN_STATE,
                        null, null, null, clock.instant(), "provider_did_not_answer", null, null);
            }
            return classify(request, operation.platformCode(), response);
        } catch (IllegalArgumentException destinationRefused) {
            return DescriptionWriteResult.refusedBeforeDispatch("outbound_destination_refused", clock.instant());
        } finally {
            resolvedSecrets.forEach(secret -> Arrays.fill(secret, '\0'));
        }
    }

    /** A single exact version, never the wildcard/list alternatives permitted by general HTTP. */
    private static boolean validPrecondition(String header, String token) {
        if (header == null || !header.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                || token == null || token.isBlank() || token.length() > 8192
                || token.chars().anyMatch(Character::isISOControl)) return false;
        if (!header.equalsIgnoreCase("If-Match")) return true;
        // RFC 9110 §§8.8.3 / 13.1.1: one strong entity-tag. Commas inside its opaque value are valid.
        if (token.length() < 2 || token.charAt(0) != '"' || token.charAt(token.length()-1) != '"') return false;
        return token.substring(1, token.length()-1).chars()
                .allMatch(c -> c == 0x21 || (c >= 0x23 && c <= 0x7e) || (c >= 0x80 && c <= 0xff));
    }

    private DescriptionWriteResult classify(DescriptionWriteRequest request, String platformCode,
                                            OutboundHttp.Response response) {
        Map<String, String> retained = new HashMap<>();
        response.headers().forEach((name, values) -> {
            if (!values.isEmpty()) {
                // Preserve multiplicity. A duplicate numeric delay becomes
                // explicit ambiguous evidence, never a silently selected value.
                retained.merge(name.toLowerCase(Locale.ROOT), String.join(", ", values),
                        (left, right) -> left + ", " + right);
            }
        });
        DescriptionWriteResult.Response transport;
        try {
            transport = new DescriptionWriteResult.Response(response.statusCode(),
                    filterRetainable(retained), request.digest(), "PROVIDER_RESPONSE",
                    response.complete());
        } catch (IllegalArgumentException notEvidence) {
            return new DescriptionWriteResult(DescriptionWriteResult.Outcome.UNKNOWN_STATE,
                    null, null, null, clock.instant(), "provider_evidence_missing_or_unbound", null, null);
        }
        Integer retryAfter = RetryAfterUnits.secondsFromHeaders(platformCode, retained).orElse(null);
        DescriptionWriteResult.Outcome proposed = response.complete() && response.statusCode() < 300
                ? DescriptionWriteResult.Outcome.ACCEPTED
                : DescriptionWriteResult.Outcome.UNKNOWN_STATE;
        return new DescriptionWriteResult(proposed, String.valueOf(response.statusCode()), null,
                response.body(), clock.instant(), null, retryAfter, transport);
    }

    private static Map<String, String> filterRetainable(Map<String, String> headers) {
        Map<String, String> retained = new LinkedHashMap<>();
        for (String name : List.of("content-type", "retry-after", "item-retry-after", "x-ratelimit-retry",
                "x-request-id", "etag", "x-version-id")) {
            String value = headers.get(name);
            if (value != null) {
                retained.put(name, value);
            }
        }
        return retained;
    }

    private Optional<String> headerValue(AuthHeaderSpec header, DescriptionWriteRequest request,
                                         List<char[]> resolvedSecrets) {
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

    /** The description vocabulary, and nothing from the price or bid ones. */
    private static Map<String, String> placeholders(DescriptionWriteRequest request) {
        Map<String, String> values = new HashMap<>();
        values.put("nativeListingKey", request.nativeListingKey());
        if (request.nativeVariantKey() != null) {
            values.put("nativeVariantKey", request.nativeVariantKey());
        }
        values.put("idempotencyKey", request.idempotencyKey());
        values.put("kizMarkedDeclared", Boolean.toString(request.kizMarkedDeclared()));
        if (request.descriptionText() != null) {
            values.put("descriptionText", request.descriptionText());
        }
        if (request.descriptionAttributeKey() != null) {
            values.put("descriptionAttributeKey", request.descriptionAttributeKey());
        }
        if (request.nativeTaskKey() != null) {
            values.put("nativeTaskKey", request.nativeTaskKey());
        }
        return values;
    }
}

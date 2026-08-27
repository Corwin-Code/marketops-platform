package com.mimococo.marketops.aicopilot.adapter.http;

import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.aicopilot.port.ModelGatewayPort;
import com.mimococo.marketops.aicopilot.port.ModelRequest;
import com.mimococo.marketops.aicopilot.port.ModelResponse;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.mimococo.marketops.shared.CorrelationId;
import java.io.IOException;
import java.net.URI;
import com.mimococo.marketops.shared.port.OutboundHttp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one doorway a model call leaves through, driven by recorded evidence.
 *
 * <p>Nothing about a provider is written here. The endpoint, the request shape,
 * where the answer lives inside the response and how the credential is presented
 * are all recorded facts carrying their own eligibility state, so a provider
 * nobody has verified has no reachable specification and the fail-closed
 * behaviour is the absence of a call.
 *
 * <p>Failure is a value rather than an exception, because an unavailable model
 * must degrade the explanation and nothing else. A caller that had to catch
 * something here would eventually forget to, and the deterministic diagnosis
 * would fail with it.
 *
 * <p>No prompt, no answer and no credential reaches a log record. The projected
 * data is bounded by the retention policy the projection declares, and a log
 * line carrying it would put it somewhere that policy does not reach.
 */
public final class HttpModelGateway implements ModelGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(HttpModelGateway.class);

    private final OutboundHttp httpClient;
    private final AiRepository repository;
    private final SecretResolverPort secrets;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HttpModelGateway(OutboundHttp httpClient,
                            AiRepository repository,
                            SecretResolverPort secrets,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ModelResponse invoke(ModelRequest request) {
        Instant startedAt = clock.instant();
        Optional<AiRepository.ProviderCallSpec> found =
                repository.eligibleProviderSpec(request.modelCode());
        if (found.isEmpty()) {
            return refuse("PROVIDER_NOT_ELIGIBLE", startedAt);
        }
        AiRepository.ProviderCallSpec spec = found.get();

        OutboundHttp.Plan plan;
        try {
            String body = renderRequest(spec.requestTemplate(), request);
            JsonNode rendered = com.mimococo.marketops.shared.JsonValues.read(objectMapper,body);
            if (rendered == null || !rendered.isObject()) throw new IllegalArgumentException("model request must be a JSON object");
            OutboundHttp.requireHeaderTemplate(spec.authValueTemplate());
            plan = httpClient.prepare(new OutboundHttp.Destination("ai:" + spec.providerCode(),
                    URI.create(spec.invocationUrl()), "POST",
                    java.util.Set.of("Content-Type", "Accept", spec.authHeaderName()),
                    body.getBytes(StandardCharsets.UTF_8), spec.requestTimeoutMillis(), 131_072));
        } catch (RuntimeException invalidDestination) {
            return refuse("DESTINATION_POLICY_REFUSED", startedAt);
        }

        Optional<char[]> secret = secrets.resolve(request.secretReference());
        if (secret.isEmpty()) {
            return refuse("CREDENTIAL_UNRESOLVABLE", startedAt);
        }
        String authorization;
        try {
            authorization = spec.authValueTemplate()
                    .replace("{value}", new String(secret.get()));
        } finally {
            Arrays.fill(secret.get(), '\0');
        }

        try {
            OutboundHttp.Response response = httpClient.exchange(plan, Map.of("Content-Type", "application/json",
                    "Accept", "application/json", spec.authHeaderName(), authorization));
            long latency = Duration.between(startedAt, clock.instant()).toMillis();
            if (!response.complete()) return ModelResponse.failed(response.failureCode(), latency);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return refuseWithStatus(response.statusCode(), latency);
            }
            return extractAnswer(response.body(), spec.responsePointer(), latency);
        } catch (IllegalArgumentException invalidDestination) {
            return refuse("DESTINATION_POLICY_REFUSED", startedAt);
        } catch (IOException transportFailure) {
            return refuse("TRANSPORT_FAILED", startedAt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return refuse("INTERRUPTED", startedAt);
        }
    }

    /**
     * Render the recorded request template.
     *
     * <p>Substituted values are escaped for a JSON string literal, because the
     * prompt carries operating data and a value containing a quotation mark
     * would otherwise change the shape of the document being sent.
     */
    private static String renderRequest(String template, ModelRequest request) {
        Map<String, String> values = Map.of(
                "model", request.modelCode(),
                "systemPrompt", request.systemPrompt(),
                "userPrompt", request.userPrompt(),
                "maxOutputTokens", Integer.toString(request.maximumOutputTokens()));
        var placeholders = java.util.regex.Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}").matcher(template);
        while (placeholders.find()) {
            if (!values.containsKey(placeholders.group(1))) throw new IllegalArgumentException("unknown model placeholder");
        }
        String rendered = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            rendered = rendered.replace("{" + value.getKey() + "}",
                    "maxOutputTokens".equals(value.getKey())
                            ? value.getValue() : jsonEscape(value.getValue()));
        }
        return rendered;
    }

    private ModelResponse extractAnswer(byte[] body, String pointer, long latencyMillis) {
        try {
            JsonNode document = com.mimococo.marketops.shared.JsonValues.read(objectMapper,body);
            if (document == null) return ModelResponse.failed("RESPONSE_NOT_READABLE", latencyMillis);
            JsonNode answer = document.at(pointer);
            if (answer.isMissingNode() || answer.isNull() || !answer.isString()) {
                return new ModelResponse(ModelResponse.Outcome.FAILED, "",
                        "ANSWER_NOT_AT_RECORDED_POINTER", latencyMillis);
            }
            return ModelResponse.answered(answer.asString(), latencyMillis);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            return new ModelResponse(ModelResponse.Outcome.FAILED, "",
                    "RESPONSE_NOT_READABLE", latencyMillis);
        }
    }

    private ModelResponse refuse(String failureCode, Instant startedAt) {
        long latency = Duration.between(startedAt, clock.instant()).toMillis();
        log.atWarn()
                .addKeyValue("event", "ai_gateway_call_refused")
                .addKeyValue("failureCode", failureCode)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A model call was refused before or during transport");
        return ModelResponse.failed(failureCode, latency);
    }

    private ModelResponse refuseWithStatus(int status, long latencyMillis) {
        log.atWarn()
                .addKeyValue("event", "ai_gateway_call_refused")
                .addKeyValue("failureCode", "PROVIDER_REFUSED")
                .addKeyValue("statusClass", (status / 100) + "xx")
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A model provider refused the call");
        return ModelResponse.failed("PROVIDER_REFUSED", latencyMillis);
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}

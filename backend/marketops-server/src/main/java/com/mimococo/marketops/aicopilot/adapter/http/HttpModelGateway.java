package com.mimococo.marketops.aicopilot.adapter.http;

import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.aicopilot.port.ModelGatewayPort;
import com.mimococo.marketops.aicopilot.port.ModelRequest;
import com.mimococo.marketops.aicopilot.port.ModelResponse;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.mimococo.marketops.shared.CorrelationId;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import com.mimococo.marketops.shared.port.OutboundHttp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
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

    /** A provider error code worth logging: an identifier, never prose. */
    private static final Pattern PROVIDER_ERROR_CODE =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    /** A placeholder in a recorded request template. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

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
            // A control character cannot travel in a header. Refused here, it is
            // named for what it is rather than surfacing later as a transport
            // policy refusal nobody could trace back to the credential file.
            for (char character : secret.get()) {
                if (Character.isISOControl(character)) {
                    return refuse("CREDENTIAL_MALFORMED", startedAt);
                }
            }
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
                return refuseWithStatus(response.statusCode(), response.body(), latency);
            }
            return extractAnswer(response.body(), spec.responsePointer(), latency);
        } catch (IllegalArgumentException invalidDestination) {
            return refuse("DESTINATION_POLICY_REFUSED", startedAt);
        } catch (InterruptedIOException deadline) {
            return refuse("RESPONSE_DEADLINE_EXCEEDED", startedAt);
        } catch (IOException transportFailure) {
            // At its deadline the transport closes the connection, which surfaces
            // as an ordinary socket error. A failure that arrives only once the
            // whole allowance is spent is a slow provider, not a broken link.
            long elapsed = Duration.between(startedAt, clock.instant()).toMillis();
            return refuse(elapsed >= spec.requestTimeoutMillis()
                    ? "RESPONSE_DEADLINE_EXCEEDED" : "TRANSPORT_FAILED", startedAt);
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
     *
     * <p>The template is rendered in one pass, so a substituted value that
     * happens to contain a placeholder's text stays text.
     */
    private static String renderRequest(String template, ModelRequest request) {
        Map<String, String> values = Map.of(
                "model", jsonEscape(request.modelCode()),
                "systemPrompt", jsonEscape(request.systemPrompt()),
                "userPrompt", jsonEscape(request.userPrompt()),
                "maxOutputTokens", Integer.toString(request.maximumOutputTokens()));
        var placeholders = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length() + request.userPrompt().length());
        while (placeholders.find()) {
            String value = values.get(placeholders.group(1));
            if (value == null) throw new IllegalArgumentException("unknown model placeholder");
            placeholders.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement(value));
        }
        placeholders.appendTail(rendered);
        return rendered.toString();
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

    /**
     * Name a provider's refusal by what an operator can do about it.
     *
     * <p>The classes are HTTP's own, so they hold for any provider: a rejected
     * credential is fixed in the secret store, throttling by waiting, a rejected
     * request by reading the provider's code, and an unavailable service by
     * nobody here. The provider's own error code is logged when it has a plain
     * shape; its message is not, because a message can echo the prompt.
     */
    private ModelResponse refuseWithStatus(int status, byte[] body, long latencyMillis) {
        String failureCode = switch (status) {
            case 401, 403 -> "PROVIDER_AUTH_REJECTED";
            case 429 -> "PROVIDER_THROTTLED";
            default -> status >= 500 ? "PROVIDER_UNAVAILABLE"
                    : status >= 400 ? "PROVIDER_REQUEST_REJECTED" : "PROVIDER_REFUSED";
        };
        var event = log.atWarn()
                .addKeyValue("event", "ai_gateway_call_refused")
                .addKeyValue("failureCode", failureCode)
                .addKeyValue("statusCode", status)
                .addKeyValue("correlationId", CorrelationId.current());
        providerErrorCode(body).ifPresent(code -> event.addKeyValue("providerErrorCode", code));
        event.log("A model provider refused the call");
        return ModelResponse.failed(failureCode, latencyMillis);
    }

    /** The provider's own error code, when the refusal carries one with a plain shape. */
    private Optional<String> providerErrorCode(byte[] body) {
        try {
            JsonNode document = com.mimococo.marketops.shared.JsonValues.read(objectMapper, body);
            if (document == null) {
                return Optional.empty();
            }
            for (String pointer : List.of("/error/code", "/code")) {
                JsonNode code = document.at(pointer);
                if (code.isString() && PROVIDER_ERROR_CODE.matcher(code.asString()).matches()) {
                    return Optional.of(code.asString());
                }
            }
        } catch (JacksonException | IllegalArgumentException unreadable) {
            // An unreadable refusal is logged without the provider's code.
        }
        return Optional.empty();
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

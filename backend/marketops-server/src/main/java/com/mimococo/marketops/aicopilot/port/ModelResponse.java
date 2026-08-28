package com.mimococo.marketops.aicopilot.port;

import java.util.Objects;
import java.util.Optional;

/**
 * What one model call returned, preserved as the provider sent it.
 *
 * <p>The body is the model's own text, unparsed. Validation happens above this
 * boundary so that a provider adapter cannot decide what a claim means, and so
 * that the same validation applies whichever provider answered.
 *
 * @param outcome how the call ended
 * @param body the model's answer, or an empty string when there was none
 * @param failureCode why the call did not succeed, or {@code null}
 * @param latencyMillis how long the call took
 */
public record ModelResponse(
        Outcome outcome, String body, String failureCode, long latencyMillis) {

    public ModelResponse {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(body, "body");
    }

    /** A call that returned an answer. */
    public static ModelResponse answered(String body, long latencyMillis) {
        return new ModelResponse(Outcome.ANSWERED, body, null, latencyMillis);
    }

    /** A call that did not return an answer. */
    public static ModelResponse failed(String failureCode, long latencyMillis) {
        return new ModelResponse(Outcome.FAILED, "", failureCode, latencyMillis);
    }

    /** The answer, when there was one. */
    public Optional<String> answer() {
        return outcome == Outcome.ANSWERED ? Optional.of(body) : Optional.empty();
    }

    /** How a model call ended. */
    public enum Outcome {

        /** The provider returned an answer. */
        ANSWERED,

        /** The provider did not answer, and the reason is recorded. */
        FAILED
    }
}

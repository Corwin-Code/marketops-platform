package com.mimococo.marketops.aicopilot.port;

import java.util.Objects;

/**
 * One call to a model, as the gateway boundary sees it.
 *
 * <p>The request carries a rendered prompt and the opaque reference naming the
 * credential to use. No secret crosses this boundary and no business object
 * does: by the time a request exists, the projection has already reduced the
 * subject to allowlisted values, so an adapter cannot reach anything the
 * projection excluded.
 *
 * @param modelCode the provider's own name for the model
 * @param secretReference opaque reference to the credential the adapter resolves
 * @param systemPrompt the instruction defining the output contract
 * @param userPrompt the projected subject, rendered
 * @param maximumOutputTokens ceiling on the answer's length
 */
public record ModelRequest(
        String modelCode,
        String secretReference,
        String systemPrompt,
        String userPrompt,
        int maximumOutputTokens) {

    public ModelRequest {
        Objects.requireNonNull(modelCode, "modelCode");
        Objects.requireNonNull(secretReference, "secretReference");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userPrompt, "userPrompt");
    }
}

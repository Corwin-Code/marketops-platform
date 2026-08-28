package com.mimococo.marketops.aicopilot.port;

/**
 * Provider-neutral invocation of an external model.
 *
 * <p>The port is the only doorway a model call may leave through, and its
 * contract carries the posture rather than trusting each adapter to remember
 * it: the request names its credential by opaque reference, the response is
 * preserved unparsed, and a failure is reported as a value rather than thrown.
 *
 * <p>Reporting failure as a value is deliberate. An unavailable model must
 * degrade the explanation and nothing else, and an exception crossing this
 * boundary would make that a decision every caller had to remember to take.
 */
public interface ModelGatewayPort {

    /** Perform one model call, reporting failure rather than throwing. */
    ModelResponse invoke(ModelRequest request);
}

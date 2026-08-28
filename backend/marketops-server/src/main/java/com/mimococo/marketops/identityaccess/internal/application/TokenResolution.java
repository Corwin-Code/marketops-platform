package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.shared.ErrorCode;

/**
 * The outcome of turning a validated token into a MarketOps identity.
 *
 * <p>A refusal carries the stable code that explains it. Returning the reason
 * rather than throwing keeps the decision in the application layer and lets the
 * web boundary choose how to render it, without either side guessing what the
 * other meant.
 */
public sealed interface TokenResolution {

    /** The token resolved to a live profile. */
    record Accepted(AuthenticatedActor actor) implements TokenResolution {
    }

    /** The token was structurally valid but is not accepted here. */
    record Refused(ErrorCode code) implements TokenResolution {
    }
}

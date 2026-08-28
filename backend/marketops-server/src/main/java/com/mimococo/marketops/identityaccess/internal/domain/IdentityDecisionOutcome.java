package com.mimococo.marketops.identityaccess.internal.domain;

/** What the identity boundary decided about one request. */
public enum IdentityDecisionOutcome {
    AUTHENTICATED,
    AUTHORIZED,
    DENIED,
    STEP_UP_REQUIRED
}
